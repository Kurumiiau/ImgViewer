package com.imgviewer.ui

import android.app.Application
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.imgviewer.data.LibraryAccess
import com.imgviewer.data.LibraryAccessChangedException
import com.imgviewer.data.LibraryRefreshPolicy
import com.imgviewer.data.MediaItem
import com.imgviewer.data.MediaLibraryReader
import com.imgviewer.data.MediaLibrarySnapshot
import com.imgviewer.data.MediaProviderUnavailableException
import com.imgviewer.data.MediaRepository
import com.imgviewer.data.RefreshRequestQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GalleryState(
    val images: List<MediaItem> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null
)

class GalleryViewModel internal constructor(
    application: Application,
    suppliedReader: MediaLibraryReader?
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, null)

    private val repository = MediaRepository(application)
    private val reader = suppliedReader ?: repository
    private val mutable = MutableStateFlow(GalleryState())
    val state = mutable.asStateFlow()
    private val requests = RefreshRequestQueue()
    private var refreshJob: Job? = null
    private var resumeJob: Job? = null
    private var observerJob: Job? = null
    private var documentWriteJob: Job? = null
    private var foreground = false
    private var lastSnapshot: MediaLibrarySnapshot? = null
    private var observedAccess: LibraryAccess? = null
    private val openedItems = mutableMapOf<Uri, MediaItem>()
    private val openedVersions = mutableMapOf<Uri, Long>()
    private var openedVersion = 0L
    private val removedUris = mutableSetOf<Uri>()
    private val favoriteOverrides = mutableMapOf<Uri, Boolean>()
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            requests.invalidate()
            if (!foreground) return
            observerJob?.cancel()
            observerJob = viewModelScope.launch { delay(350); startRefresh() }
        }
    }

    init {
        application.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        startRefresh()
    }

    /** Full access only needs a cheap mounted-volume stamp when the app returns. */
    fun onResume() {
        foreground = true
        observerJob?.cancel()
        resumeJob?.cancel()
        resumeJob = viewModelScope.launch {
            val access = reader.currentAccess()
            val accessChanged = observedAccess != null && observedAccess != access
            applyAccessScope(access)
            // The initial read is already doing this work. Never restart its cursor on resume.
            if (refreshJob?.isActive == true) {
                if (accessChanged || access == LibraryAccess.PARTIAL) requests.invalidate()
                return@launch
            }
            val revision = reader.currentRevision()
            val previous = lastSnapshot
            if (LibraryRefreshPolicy.shouldReadOnResume(access, previous?.access,
                    requests.dirty, previous?.revision, revision)) {
                requests.invalidate()
                startRefresh()
            } else refreshIndependentFiles(previous!!)
        }
    }

    fun onPause() {
        foreground = false
        observerJob?.cancel()
        resumeJob?.cancel()
    }

    /** SAF documents and private/external files do not share MediaStore's generation counter. */
    private suspend fun refreshIndependentFiles(previous: MediaLibrarySnapshot) {
        if (previous.importedImages.isEmpty() && openedItems.keys.all { uri ->
                previous.libraryImages.any { it.uri == uri } }) return
        val startedAt = requests.begin()
        try {
            val imported = reader.loadImportedImages().filterNot { it.uri in removedUris }
            val snapshot = previous.copy(importedImages = imported)
            val images = reconcileOpened(snapshot)
            val access = reader.currentAccess()
            if (access != snapshot.access) throw LibraryAccessChangedException(access)
            if (requests.hasNewRequest(startedAt) || lastSnapshot !== previous) return
            lastSnapshot = snapshot
            val currentOpened = openedItems.values.filter { it.uri !in removedUris }
            mutable.update { it.copy(images = (images + currentOpened).distinctBy { photo -> photo.uri }
                .sortedWith(compareByDescending<MediaItem> { photo -> photo.dateTaken }.thenBy { photo -> photo.name }
                    .thenBy { photo -> photo.uri.toString() }), error = null) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (changed: LibraryAccessChangedException) {
            applyAccessScope(changed.access)
            requests.invalidate()
            startRefresh()
        } catch (_: Exception) {
            // The normal worker gives unavailable document providers the same bounded recovery
            // as MediaStore. A failed auxiliary check never removes the previous thumbnails.
            requests.invalidate()
            startRefresh()
        }
    }

    /** User refresh, imports and permission results force a read without cancelling an active one. */
    fun reload() {
        requests.invalidate()
        startRefresh()
    }

    private fun startRefresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            while (requests.dirty) {
                val startedAt = requests.begin()
                val accepted = readWithRetry(startedAt)
                if (accepted) requests.accept(startedAt)
                // New requests are merged into one following pass; failures remain dirty for resume.
                if (!foreground && requests.hasNewRequest(startedAt)) break
                if (!accepted && !requests.hasNewRequest(startedAt)) break
            }
        }
    }

    private suspend fun readWithRetry(startedAt: Long): Boolean {
        var lastFailure: Exception? = null
        for (attempt in 0..3) {
            try {
                if (requests.hasNewRequest(startedAt)) return true
                applyAccessScope(reader.currentAccess())
                val read = reader.loadSnapshot()
                val snapshot = read.copy(libraryImages = read.libraryImages.filterNot { it.uri in removedUris },
                    importedImages = read.importedImages.filterNot { it.uri in removedUris })
                val access = reader.currentAccess()
                if (snapshot.access != access) throw LibraryAccessChangedException(access)
                applyAccessScope(access)
                if (requests.hasNewRequest(startedAt)) return true
                if (LibraryRefreshPolicy.needsEmptyConfirmation(access, snapshot.libraryImages.size, attempt)) {
                    delay(LibraryRefreshPolicy.retryDelayMillis(attempt))
                    continue
                }
                val previous = lastSnapshot
                if (access == LibraryAccess.FULL && snapshot.libraryImages.isEmpty() &&
                    previous?.libraryImages?.isNotEmpty() == true) {
                    // Deletions/unmounting change the revision. An unchanged known stamp cannot
                    // honestly turn a nonempty library into an empty one.
                    val unchanged = previous.revision != null && previous.revision == snapshot.revision
                    val visible = if (snapshot.revision?.volumes?.isEmpty() == true) false
                        else reader.knownImageStillVisible(previous.libraryImages)
                    if (unchanged || visible || (snapshot.revision == null && attempt < 3)) {
                        throw MediaProviderUnavailableException("系统暂时未返回照片，正在自动重试。")
                    }
                }
                val images = reconcileOpened(snapshot)
                val finalAccess = reader.currentAccess()
                if (snapshot.access != finalAccess) throw LibraryAccessChangedException(finalAccess)
                // A delete/import/selection change that arrived during this query owns the next
                // publication. Do not briefly resurrect a deleted row or an older grant range.
                if (requests.hasNewRequest(startedAt)) return true
                lastSnapshot = snapshot
                observedAccess = finalAccess
                val currentOpened = openedItems.values.filter { it.uri !in removedUris }
                val finalImages = (images + currentOpened).distinctBy { it.uri }
                    .sortedWith(compareByDescending<MediaItem> { it.dateTaken }.thenBy { it.name }.thenBy { it.uri.toString() })
                mutable.update { it.copy(images = finalImages, loading = false, error = null) }
                return true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (changed: LibraryAccessChangedException) {
                applyAccessScope(changed.access)
                lastFailure = changed
            } catch (failure: Exception) {
                lastFailure = failure
            }
            if (attempt < 3) delay(LibraryRefreshPolicy.retryDelayMillis(attempt))
        }
        if (requests.hasNewRequest(startedAt)) return true
        mutable.update { it.copy(loading = false,
            error = "读取照片失败：${lastFailure?.localizedMessage ?: "请稍后刷新重试"}") }
        return false
    }

    /** Drop the former permission range immediately, while retaining individually granted imports. */
    private fun applyAccessScope(access: LibraryAccess) {
        if (LibraryRefreshPolicy.permissionScopeReduced(observedAccess, access)) {
            val importedUris = lastSnapshot?.importedImages?.mapTo(hashSetOf()) { it.uri }.orEmpty()
            mutable.update { current -> current.copy(images = current.images.filter { it.uri in importedUris }) }
            // Opened media must be resolved under the new grant, never copied blindly into it.
        }
        observedAccess = access
    }

    private suspend fun reconcileOpened(snapshot: MediaLibrarySnapshot): List<MediaItem> {
        val loadedUris = snapshot.images.mapTo(hashSetOf()) { it.uri }
        val opened = mutableListOf<MediaItem>()
        for (item in openedItems.values.toList()) {
            if (item.uri in loadedUris) continue
            val version = openedVersions[item.uri]
            // A missing file or withdrawn document grant is removed; temporary provider failure
            // aborts this publication and retains the last good snapshot instead.
            val resolved = repository.resolveItem(item.uri, failOnProviderFailure = true)
            if (openedVersions[item.uri] != version) continue
            if (resolved == null) {
                openedItems.remove(item.uri)
                openedVersions.remove(item.uri)
            } else {
                openedItems[item.uri] = resolved
                opened += resolved
            }
        }
        return (snapshot.images + opened).distinctBy { it.uri }.map { item ->
            favoriteOverrides[item.uri]?.let { item.copy(isFavorite = it) } ?: item
        }.sortedWith(compareByDescending<MediaItem> { it.dateTaken }.thenBy { it.name }.thenBy { it.uri.toString() })
    }

    fun importImages(uris: List<Uri>) {
        val previousWrite = documentWriteJob
        documentWriteJob = viewModelScope.launch {
            try {
                previousWrite?.join()
                val imported = repository.importDocuments(uris)
                removedUris.removeAll(imported.toSet())
                reload()
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(error = "导入失败：${failure.localizedMessage}") } }
        }
    }

    fun openExternal(uri: Uri, onReady: (MediaItem) -> Unit) {
        viewModelScope.launch {
            try {
                val item = repository.resolveItem(uri, failOnProviderFailure = true)
                if (item != null) {
                    removedUris.remove(uri)
                    openedItems[uri] = item
                    openedVersions[uri] = ++openedVersion
                    mutable.update { it.copy(images = (it.images.filterNot { old -> old.uri == uri } + item).sortedByDescending { image -> image.dateTaken }) }
                    onReady(item)
                } else mutable.update { it.copy(error = "无法读取这张图片，请重新选择文件") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(error = "无法打开：${failure.localizedMessage}") } }
        }
    }

    fun toggleFavorite(item: MediaItem) {
        viewModelScope.launch {
            val favorite = !item.isFavorite
            try {
                repository.setFavorite(item, favorite)
                favoriteOverrides[item.uri] = favorite
                openedItems[item.uri]?.let { openedItems[item.uri] = it.copy(isFavorite = favorite) }
                mutable.update { it.copy(images = it.images.map { photo -> if (photo.uri == item.uri) photo.copy(isFavorite = favorite) else photo }) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(error = "收藏失败：${failure.localizedMessage}") } }
        }
    }

    fun removed(uri: Uri) = removedMany(listOf(uri))

    fun removedMany(uris: Collection<Uri>) {
        val deleted = uris.toSet()
        if (deleted.isEmpty()) return
        requests.invalidate()
        removedUris += deleted
        deleted.forEach { uri -> openedItems.remove(uri); openedVersions.remove(uri); favoriteOverrides.remove(uri) }
        lastSnapshot = lastSnapshot?.let { it.copy(libraryImages = it.libraryImages.filterNot { item -> item.uri in deleted },
            importedImages = it.importedImages.filterNot { item -> item.uri in deleted }) }
        mutable.update { it.copy(images = it.images.filterNot { item -> item.uri in deleted }) }
        val previousWrite = documentWriteJob
        documentWriteJob = viewModelScope.launch {
            try { previousWrite?.join(); repository.forgetImportedMany(deleted); reload() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(error = "更新图集失败：${failure.localizedMessage}") } }
        }
    }

    suspend fun deletePrivateCopy(uri: Uri): Boolean = repository.deletePrivateCopy(uri)
    fun clearError() { mutable.update { it.copy(error = null) } }

    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(observer)
        super.onCleared()
    }
}
