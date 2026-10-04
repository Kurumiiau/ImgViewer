package com.imgviewer.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Parcel
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Original encoded bytes are shared, including every frame in animated images. */
internal data class PreparedImageShare(
    val intent: Intent,
    val additionalIntents: List<Intent> = emptyList(),
    val copiedFiles: List<File> = emptyList()
) {
    val intents: List<Intent> get() = listOf(intent) + additionalIntents
}

internal fun commonImageMime(types: List<String>): String {
    val normalized = types.map { it.lowercase().substringBefore(';').trim() }.distinct()
    return when {
        normalized.size == 1 && normalized[0].startsWith("image/") -> normalized[0]
        normalized.all { it.startsWith("image/") } -> "image/*"
        else -> "*/*"
    }
}

private fun imageShareIntent(uris: List<Uri>, mimeTypes: List<String>): Intent {
    require(uris.isNotEmpty())
    return Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
        type = commonImageMime(mimeTypes)
        if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.single())
        else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        clipData = ClipData("照片", mimeTypes.distinct().toTypedArray(), ClipData.Item(uris.first())).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun parcelBytes(intent: Intent): Int {
    val parcel = Parcel.obtain()
    return try { intent.writeToParcel(parcel, 0); parcel.dataSize() } finally { parcel.recycle() }
}

/** Leave room in Binder's shared transaction buffer for the chooser and URI grants. */
internal const val SHARE_INTENT_BYTE_BUDGET = 256 * 1024

internal suspend fun prepareImageShare(context: Context, items: List<MediaItem>): PreparedImageShare = withContext(Dispatchers.IO) {
    val distinct = items.distinctBy { it.uri }
    require(distinct.isNotEmpty()) { "请选择照片" }
    val app = context.applicationContext
    val resolver = app.contentResolver
    val shareRoot = File(app.cacheDir, "shared_images").canonicalFile
    check(shareRoot.exists() || shareRoot.mkdirs()) { "无法创建分享缓存" }
    val session = File(shareRoot, UUID.randomUUID().toString())
    val copies = mutableListOf<File>()
    val uris = ArrayList<Uri>(distinct.size)
    val types = ArrayList<String>(distinct.size)
    try {
        // Previously shared copies remain available while the receiving app uses its URI grant.
        // Only expired, known session directories beneath this narrow cache root are cleaned.
        val expiry = System.currentTimeMillis() - 48L * 60 * 60 * 1000
        shareRoot.listFiles()?.filter { it.isDirectory && it.lastModified() < expiry }?.forEach { old ->
            if (old.canonicalFile.parentFile == shareRoot) old.deleteRecursively()
        }
        distinct.forEachIndexed { index, item ->
            currentCoroutineContext().ensureActive()
            val providerAllowsGrant = item.uri.scheme == "content" &&
                (item.uri.authority == MediaStore.AUTHORITY ||
                    item.uri.authority == "${app.packageName}.fileprovider" ||
                    app.packageManager.resolveContentProvider(item.uri.authority.orEmpty(), 0)?.grantUriPermissions == true)
            val uri = if (providerAllowsGrant) {
                resolver.openInputStream(item.uri)?.use { } ?: error("无法读取「${item.name}」")
                item.uri
            } else {
                check(session.exists() || session.mkdirs()) { "无法创建分享缓存" }
                val name = item.name.substringAfterLast('/').substringAfterLast('\\')
                    .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").takeLast(160).ifBlank { "image" }
                val target = File(session, "${index + 1}_$name")
                val input = if (item.uri.scheme == "file") File(requireNotNull(item.uri.path)).inputStream()
                    else resolver.openInputStream(item.uri) ?: error("无法读取「${item.name}」")
                input.use { source -> target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                } }
                copies += target
                FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", target)
            }
            uris += uri
            types += item.mimeType.ifBlank { runCatching { resolver.getType(uri) }.getOrNull() ?: "image/*" }
        }
        // There is no fixed image-count limit. Split only when the actual serialized URI payload
        // exceeds the budget; use binary search so a large selection avoids quadratic rebuilding.
        val intents = mutableListOf<Intent>()
        var start = 0
        while (start < uris.size) {
            currentCoroutineContext().ensureActive()
            var low = start + 1
            var high = uris.size
            var end = low
            while (low <= high) {
                val mid = low + (high - low) / 2
                if (parcelBytes(imageShareIntent(uris.subList(start, mid), types.subList(start, mid))) <= SHARE_INTENT_BYTE_BUDGET) {
                    end = mid
                    low = mid + 1
                } else high = mid - 1
            }
            val intent = imageShareIntent(uris.subList(start, end), types.subList(start, end))
            check(parcelBytes(intent) <= SHARE_INTENT_BYTE_BUDGET) { "文件地址过长，无法分享" }
            intents += intent
            start = end
        }
        PreparedImageShare(intents.first(), intents.drop(1), copies.toList())
    } catch (failure: Throwable) {
        if (session.canonicalFile.parentFile == shareRoot) session.deleteRecursively()
        throw failure
    }
}
