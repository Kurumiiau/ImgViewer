package com.imgviewer.ui

import android.app.Activity
import android.app.Application
import android.app.RecoverableSecurityException
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.imgviewer.data.MediaItem
import com.imgviewer.data.PreparedImageShare
import com.imgviewer.data.mediaDeleteChunks
import com.imgviewer.data.prepareImageShare
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class DeletePrompt(val token: Long, val sender: IntentSender, val launched: Boolean = false)

internal data class BatchActionState(
    val confirmation: List<MediaItem>? = null,
    val busy: Boolean = false,
    val deletePrompt: DeletePrompt? = null,
    val message: String? = null,
    val preparedShare: PreparedImageShare? = null,
    val shareIndex: Int = 0,
    val shareOpened: Boolean = false
)

/** Queues live in memory across rotation, never in the Activity's Binder-sized saved state. */
internal class BatchActionsViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(BatchActionState())
    val state = mutable.asStateFlow()
    private var nextToken = 0L
    private var awaitingDelete: CompletableDeferred<Int>? = null

    fun requestDelete(items: List<MediaItem>) {
        if (mutable.value.busy || mutable.value.confirmation != null) return
        val distinct = items.distinctBy { it.uri }
        if (distinct.isNotEmpty()) mutable.update { it.copy(confirmation = distinct) }
    }

    fun dismissDelete() { mutable.update { it.copy(confirmation = null) } }
    fun clearMessage() { mutable.update { it.copy(message = null) } }

    fun claimDeletePrompt(token: Long): Boolean {
        val prompt = mutable.value.deletePrompt
        if (prompt?.token != token || prompt.launched) return false
        mutable.update { it.copy(deletePrompt = prompt.copy(launched = true)) }
        return true
    }

    fun receiveDeleteResult(resultCode: Int) {
        awaitingDelete?.complete(resultCode)
    }

    fun receiveDeleteLaunchFailure(failure: Exception) {
        awaitingDelete?.completeExceptionally(failure)
    }

    private suspend fun awaitSystemDelete(sender: IntentSender): Boolean {
        val response = CompletableDeferred<Int>()
        awaitingDelete = response
        mutable.update { it.copy(deletePrompt = DeletePrompt(++nextToken, sender)) }
        return try { response.await() == Activity.RESULT_OK }
        finally { awaitingDelete = null; mutable.update { it.copy(deletePrompt = null) } }
    }

    fun confirmDelete(
        deletePrivateCopy: suspend (Uri) -> Boolean,
        onRemoved: (List<Uri>) -> Unit
    ) {
        val items = mutable.value.confirmation ?: return
        if (mutable.value.busy) return
        mutable.update { it.copy(confirmation = null, busy = true, message = null) }
        viewModelScope.launch {
            val app = getApplication<Application>()
            val resolver = app.contentResolver
            val removed = mutableListOf<Uri>()
            var failures = 0
            var firstFailure: String? = null
            var cancelled = false
            var published = 0
            fun publishRemoved() {
                if (removed.size > published) {
                    onRemoved(removed.subList(published, removed.size).toList())
                    published = removed.size
                }
            }
            fun failed(item: MediaItem, failure: Exception? = null) {
                failures++
                if (firstFailure == null) firstFailure = "「${item.name}」${failure?.localizedMessage?.let { ": $it" } ?: "无法删除"}"
            }
            try {
                val systemItems = if (Build.VERSION.SDK_INT >= 30) items.filter { item ->
                    item.uri.scheme == "content" && item.uri.authority == MediaStore.AUTHORITY &&
                        item.uri.lastPathSegment?.toLongOrNull() != null
                } else emptyList()
                val systemUris = systemItems.mapTo(hashSetOf()) { it.uri }
                // Ask MediaStore before deleting documents/private copies, so cancelling its first
                // prompt does not silently delete a different subset of the user's selection.
                for (chunk in mediaDeleteChunks(systemItems)) {
                    if (Build.VERSION.SDK_INT < 30) break
                    val approved = try {
                        val request = withContext(Dispatchers.IO) {
                            if (Build.VERSION.SDK_INT >= 30) {
                                MediaStore.createDeleteRequest(resolver, chunk.map { it.uri })
                            } else error("当前系统不支持批量删除请求")
                        }
                        awaitSystemDelete(request.intentSender)
                    } catch (stopped: CancellationException) { throw stopped }
                    catch (failure: Exception) { chunk.forEach { failed(it, failure) }; continue }
                    if (!approved) { cancelled = true; break }
                    // RESULT_OK means the provider has completed the operation. Still verify the
                    // specific rows, so a provider's partial failure does not hide surviving files.
                    withContext(Dispatchers.IO) {
                        val collections = chunk.groupBy { item ->
                            item.uri.buildUpon().encodedPath(item.uri.encodedPath.orEmpty().substringBeforeLast('/')).build()
                        }
                        for ((collection, members) in collections) for (group in members.chunked(400)) {
                            try {
                                val ids = group.map { it.uri.lastPathSegment!! }
                                val placeholders = List(ids.size) { "?" }.joinToString(",")
                                val remaining = resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
                                    "${MediaStore.MediaColumns._ID} IN ($placeholders)", ids.toTypedArray(), null)?.use { cursor ->
                                    val column = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                                    buildSet<String> { while (cursor.moveToNext()) add(cursor.getLong(column).toString()) }
                                } ?: error("系统暂时无法确认删除结果")
                                group.forEach { item ->
                                    if (item.uri.lastPathSegment !in remaining) removed += item.uri else failed(item)
                                }
                            } catch (stopped: CancellationException) { throw stopped }
                            catch (failure: Exception) { group.forEach { failed(it, failure) } }
                        }
                    }
                    publishRemoved()
                }
                if (!cancelled) for (item in items.filterNot { it.uri in systemUris }) {
                    try {
                        suspend fun deleteOne(): Boolean = withContext(Dispatchers.IO) {
                            when {
                                item.uri.scheme == "file" -> deletePrivateCopy(item.uri)
                                DocumentsContract.isDocumentUri(app, item.uri) -> DocumentsContract.deleteDocument(resolver, item.uri)
                                else -> resolver.delete(item.uri, null, null) > 0
                            }
                        }
                        val deleted = try { deleteOne() }
                        catch (security: Exception) {
                            if (Build.VERSION.SDK_INT < 29 || security !is RecoverableSecurityException) throw security
                            if (!awaitSystemDelete(security.userAction.actionIntent.intentSender)) {
                                cancelled = true
                                break
                            }
                            deleteOne()
                        }
                        if (deleted) removed += item.uri else failed(item)
                    } catch (stopped: CancellationException) { throw stopped }
                    catch (failure: Exception) { failed(item, failure) }
                    if (removed.size - published >= 32) publishRemoved()
                }
                publishRemoved()
                val message = when {
                    cancelled && removed.isEmpty() -> "已取消删除，照片已保留"
                    cancelled -> "已删除 ${removed.size} 张，剩余照片已保留"
                    failures > 0 -> "已删除 ${removed.size} 张，${failures} 张未能删除，可再次选择重试。${firstFailure.orEmpty()}"
                    removed.size == 1 -> "照片已删除"
                    else -> "已删除 ${removed.size} 张照片"
                }
                mutable.update { it.copy(message = message) }
            } catch (stopped: CancellationException) { throw stopped }
            finally { mutable.update { it.copy(busy = false, deletePrompt = null) } }
        }
    }

    fun prepareShare(items: List<MediaItem>) {
        if (mutable.value.busy || items.isEmpty()) return
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val prepared = prepareImageShare(getApplication(), items)
                mutable.update { it.copy(preparedShare = prepared, shareIndex = 0, shareOpened = false) }
            } catch (stopped: CancellationException) { throw stopped }
            catch (failure: Exception) {
                mutable.update { it.copy(busy = false, message = "无法分享：${failure.localizedMessage ?: "请重新选择照片"}") }
            }
        }
    }

    fun claimShare(): Boolean {
        if (mutable.value.preparedShare == null || mutable.value.shareOpened) return false
        mutable.update { it.copy(shareOpened = true) }
        return true
    }

    fun shareReturned() {
        val current = mutable.value
        val share = current.preparedShare ?: return
        val next = current.shareIndex + 1
        if (next >= share.intents.size) finishShare()
        else mutable.update { it.copy(shareIndex = next, shareOpened = false) }
    }

    fun finishShare(error: String? = null) {
        mutable.update { it.copy(busy = false, preparedShare = null, shareIndex = 0, shareOpened = false,
            message = error ?: it.message) }
    }
}
