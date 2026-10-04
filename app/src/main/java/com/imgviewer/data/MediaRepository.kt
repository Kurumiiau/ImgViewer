package com.imgviewer.data

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Process
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import com.imgviewer.image.MediaHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resumeWithException

/** A scoped-storage gallery. No broad filesystem access and no location metadata permission. */
class MediaRepository(context: Context) : MediaLibraryReader {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val preferences = appContext.getSharedPreferences("imgviewer_media", Context.MODE_PRIVATE)
    private val writes = Mutex()
    private val headerCache = ConcurrentHashMap<String, MediaHeader.Info>()

    /** Compatibility API: failed providers throw instead of pretending the gallery is empty. */
    suspend fun loadImages(): List<MediaItem> = loadSnapshot().images

    override fun currentAccess(): LibraryAccess = when {
        ContextCompat.checkSelfPermission(appContext, if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED -> LibraryAccess.FULL
        Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(appContext,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED -> LibraryAccess.PARTIAL
        else -> LibraryAccess.NONE
    }

    /** Read only real mounted volumes. Failed / unsupported metadata never becomes a trusted stamp. */
    override suspend fun currentRevision(): LibraryRevision? = withContext(Dispatchers.IO) {
        readRevision()
    }

    override suspend fun loadSnapshot(): MediaLibrarySnapshot = withContext(Dispatchers.IO) {
        val accessBefore = currentAccess()
        val beforeRevision = readRevision()
        val favorites = preferences.getStringSet(FAVORITES, emptySet()).orEmpty().toSet()
        val imported = loadImportedInternal(favorites)
        val library = try {
            queryLibrary(favorites)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (denied: SecurityException) {
            val after = currentAccess()
            if (after != accessBefore) throw LibraryAccessChangedException(after)
            if (after != LibraryAccess.NONE) {
                throw MediaProviderUnavailableException("系统暂时无法读取照片，正在自动重试。", denied)
            }
            emptyList() // A genuine denied grant keeps only individually authorized imports.
        } catch (failure: Exception) {
            coroutineContext.ensureActive()
            if (failure is MediaProviderUnavailableException) throw failure
            throw MediaProviderUnavailableException("照片服务暂时不可用，正在自动重试。", failure)
        }
        val accessAfter = currentAccess()
        if (accessAfter != accessBefore) throw LibraryAccessChangedException(accessAfter)
        val afterRevision = readRevision()
        MediaLibrarySnapshot(library, imported, accessAfter,
            afterRevision.takeIf { beforeRevision != null && beforeRevision == it })
    }

    override suspend fun loadImportedImages(): List<MediaItem> = withContext(Dispatchers.IO) {
        loadImportedInternal(preferences.getStringSet(FAVORITES, emptySet()).orEmpty().toSet())
    }

    /** A nonempty row still visible/readable contradicts a supposedly empty full-library query. */
    override suspend fun knownImageStillVisible(previous: List<MediaItem>): Boolean = withContext(Dispatchers.IO) {
        val candidates = previous.filter { it.uri.authority == MediaStore.AUTHORITY }
        val sample = if (candidates.size <= 12) candidates else (0..11).map { candidates[it * (candidates.size - 1) / 11] }
        for (item in sample) {
            coroutineContext.ensureActive()
            val cursor = queryCursor(item.uri, arrayOf(MediaStore.Images.Media._ID), visibleFilter(), null)
                ?: throw MediaProviderUnavailableException("照片服务未返回读取结果。")
            val visible = cursor.use { it.moveToFirst() }
            if (visible) {
                try {
                    resolver.openAssetFileDescriptor(item.uri, "r")?.use { return@withContext true }
                        ?: throw MediaProviderUnavailableException("系统暂时无法打开照片。")
                } catch (_: java.io.FileNotFoundException) { /* The indexed file was actually removed. */ }
            }
        }
        false
    }

    private suspend fun queryLibrary(favorites: Set<String>): List<MediaItem> {
        val media = mutableListOf<MediaItem>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.SIZE,
        )
        val cursor = queryCursor(collection, projection, visibleFilter(), "${MediaStore.Images.Media.DATE_TAKEN} DESC")
            ?: throw MediaProviderUnavailableException("照片服务未返回读取结果。")
        cursor.use {
                while (cursor.moveToNext()) {
                    coroutineContext.ensureActive()
                    val id = cursor.long(MediaStore.Images.Media._ID)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val name = cursor.string(MediaStore.Images.Media.DISPLAY_NAME).ifBlank { "图片 $id" }
                    val mime = cursor.string(MediaStore.Images.Media.MIME_TYPE).ifBlank { mimeFromName(name) }
                    val date = MediaDates.chooseDate(
                        cursor.long(MediaStore.Images.Media.DATE_TAKEN),
                        cursor.long(MediaStore.Images.Media.DATE_MODIFIED),
                        cursor.long(MediaStore.Images.Media.DATE_ADDED),
                    )
                    val size = cursor.long(MediaStore.Images.Media.SIZE)
                    val animated = animationCandidate(uri, name, mime, size, date, inspectGif = false)
                    media += MediaItem(
                        id = id, uri = uri, name = name, mimeType = mime, dateTaken = date,
                        albumId = cursor.string(MediaStore.Images.Media.BUCKET_ID).ifBlank { "media" },
                        albumName = cursor.string(MediaStore.Images.Media.BUCKET_DISPLAY_NAME).ifBlank { "其他图片" },
                        width = cursor.int(MediaStore.Images.Media.WIDTH), height = cursor.int(MediaStore.Images.Media.HEIGHT),
                        size = size, isAnimatedCandidate = animated, isFavorite = uri.toString() in favorites,
                    )
                }
        }
        return media
    }

    private fun loadImportedInternal(favorites: Set<String>): List<MediaItem> {
        val media = mutableListOf<MediaItem>()
        for (uriText in preferences.getStringSet(IMPORTS, emptySet()).orEmpty().toSet()) {
            val item = resolveInternal(Uri.parse(uriText), favorites, failOnProviderFailure = true) ?: continue
            if (media.none { it.uri == item.uri }) media += item
        }
        return media
    }

    private fun visibleFilter(): String? = buildList {
        if (Build.VERSION.SDK_INT >= 29) add("${MediaStore.Images.Media.IS_PENDING} = 0")
        if (Build.VERSION.SDK_INT >= 30) add("${MediaStore.Images.Media.IS_TRASHED} = 0")
    }.joinToString(" AND ").ifBlank { null }

    private fun readRevision(): LibraryRevision? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            val volumes = MediaStore.getExternalVolumeNames(appContext)
            val stamps = volumes.associateWith { volume ->
                val version: String? = MediaStore.getVersion(appContext, volume)
                if (version.isNullOrBlank()) return null
                VolumeRevision(version, MediaStore.getGeneration(appContext, volume))
            }
            LibraryRevision(stamps)
        } catch (_: Exception) { null }
    }

    private suspend fun queryCursor(uri: Uri, projection: Array<String>, selection: String?, sort: String?): Cursor? =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                val cursor = resolver.query(uri, projection, selection, null, sort, signal)
                continuation.resume(cursor) { _, abandoned, _ -> abandoned?.close() }
            } catch (failure: Exception) {
                continuation.resumeWithException(failure)
            }
    }

    suspend fun setFavorite(item: MediaItem, favorite: Boolean) = withContext(Dispatchers.IO) {
        writes.withLock {
            val values = preferences.getStringSet(FAVORITES, emptySet()).orEmpty().toMutableSet()
            if (favorite) values += item.uri.toString() else values -= item.uri.toString()
            if (!preferences.edit().putStringSet(FAVORITES, values).commit()) throw IOException("收藏保存失败，请重试。")
        }
    }

    suspend fun isFavorite(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        uri.toString() in preferences.getStringSet(FAVORITES, emptySet()).orEmpty()
    }

    /** OpenDocument grants are retained; providers without durable grants are copied into private storage. */
    suspend fun importDocuments(uris: List<Uri>) = withContext(Dispatchers.IO) {
        val importedUris = mutableListOf<Uri>()
        for (originalUri in uris.distinct()) {
            coroutineContext.ensureActive()
            val persistedUri = try {
                resolver.takePersistableUriPermission(originalUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                originalUri
            } catch (_: SecurityException) {
                copyToPrivateStorage(originalUri)
            } catch (_: IllegalArgumentException) {
                copyToPrivateStorage(originalUri)
            }
            writes.withLock {
                val values = preferences.getStringSet(IMPORTS, emptySet()).orEmpty().toMutableSet()
                values += persistedUri.toString()
                val editor = preferences.edit().putStringSet(IMPORTS, values)
                if (!preferences.contains(importDateKey(persistedUri))) {
                    editor.putLong(importDateKey(persistedUri), System.currentTimeMillis())
                }
                if (!editor.commit()) throw IOException("导入记录保存失败，请重试。")
            }
            importedUris += persistedUri
        }
        importedUris.toList()
    }

    suspend fun resolveItem(uri: Uri, failOnProviderFailure: Boolean = false): MediaItem? = withContext(Dispatchers.IO) {
        resolveInternal(uri, preferences.getStringSet(FAVORITES, emptySet()).orEmpty().toSet(), failOnProviderFailure)
    }

    /** Remove a deleted item's record. Deleting original files is handled through the system UI. */
    suspend fun forgetImported(uri: Uri) = forgetImportedMany(listOf(uri))

    suspend fun forgetImportedMany(uris: Collection<Uri>) = withContext(Dispatchers.IO) {
        writes.withLock {
            val removed = uris.mapTo(hashSetOf()) { it.toString() }
            val imports = preferences.getStringSet(IMPORTS, emptySet()).orEmpty().toMutableSet().apply { removeAll(removed) }
            val favorites = preferences.getStringSet(FAVORITES, emptySet()).orEmpty().toMutableSet().apply { removeAll(removed) }
            val editor = preferences.edit().putStringSet(IMPORTS, imports).putStringSet(FAVORITES, favorites)
            uris.forEach { editor.remove(importDateKey(it)) }
            if (!editor.commit()) throw IOException("移除导入记录失败。")
        }
    }

    /** Only an app-owned import copy can be deleted through this method. */
    suspend fun deletePrivateCopy(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        if (uri.scheme != ContentResolver.SCHEME_FILE) return@withContext false
        val path = uri.path ?: return@withContext false
        val importsDirectory = File(appContext.filesDir, "imported").canonicalFile
        val file = File(path).canonicalFile
        if (file.parentFile != importsDirectory || !file.isFile) return@withContext false
        file.delete()
    }

    private fun resolveInternal(uri: Uri, favorites: Set<String>, failOnProviderFailure: Boolean = false): MediaItem? {
        try {
            var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
            var size = 0L
            var dateTaken = 0L
            var lastModified = 0L
            var albumId = "imported"
            var albumName = "导入的图片"
            var width = 0
            var height = 0
            var id = stableId(uri.toString())
            var mime = if (uri.scheme == ContentResolver.SCHEME_FILE) "" else resolver.getType(uri).orEmpty()
            if (uri.scheme == ContentResolver.SCHEME_FILE) {
                val file = File(uri.path ?: return null)
                if (!file.isFile) return null
                name = file.name.substringAfter('_')
                size = file.length()
                lastModified = file.lastModified()
            } else {
                val metadata = resolver.query(uri, null, null, null, null)
                if (metadata == null && failOnProviderFailure) throw MediaProviderUnavailableException("文件服务暂时不可用。")
                metadata?.use { cursor ->
                    if (!cursor.moveToFirst()) return null
                    name = cursor.string(OpenableColumns.DISPLAY_NAME).ifBlank { name }
                    size = cursor.long(OpenableColumns.SIZE)
                    id = cursor.long(MediaStore.Images.Media._ID).takeIf { it > 0 } ?: id
                    mime = cursor.string(MediaStore.Images.Media.MIME_TYPE).ifBlank { mime }
                    dateTaken = MediaDates.chooseDate(
                        cursor.long(MediaStore.Images.Media.DATE_TAKEN),
                        cursor.long(MediaStore.Images.Media.DATE_MODIFIED),
                        cursor.long(MediaStore.Images.Media.DATE_ADDED),
                    )
                    lastModified = cursor.long(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    albumId = cursor.string(MediaStore.Images.Media.BUCKET_ID).ifBlank { albumId }
                    albumName = cursor.string(MediaStore.Images.Media.BUCKET_DISPLAY_NAME).ifBlank { albumName }
                    width = cursor.int(MediaStore.Images.Media.WIDTH)
                    height = cursor.int(MediaStore.Images.Media.HEIGHT)
                }
            }
            val header = MediaHeader.read(resolver, uri)
            val rawExtension = name.substringAfterLast('.', "").lowercase() in setOf(
                "dng", "cr2", "crw", "nef", "nrw", "arw", "rw2", "orf", "pef", "srw", "raf")
            if (header.format != MediaHeader.Format.UNKNOWN && !rawExtension) mime = header.format.mimeType
            if (mime.isBlank() || mime == "application/octet-stream") mime = mimeFromName(name)
            if (width <= 0) width = header.width
            if (height <= 0) height = header.height
            if (width <= 0 || height <= 0) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                width = bounds.outWidth.coerceAtLeast(0)
                height = bounds.outHeight.coerceAtLeast(0)
            }
            val date = dateTaken.takeIf { it > 0 } ?: lastModified.takeIf { it > 0 }
                ?: preferences.getLong(importDateKey(uri), 0L)
            return MediaItem(
                id = id, uri = uri, name = name.ifBlank { "导入的图片" }, mimeType = mime,
                dateTaken = date, albumId = albumId, albumName = albumName, width = width, height = height,
                size = size, isAnimatedCandidate = header.isAnimated ||
                    (!header.animationKnown && isPotentiallyAnimated(name, mime)),
                isFavorite = uri.toString() in favorites,
            )
        } catch (denied: SecurityException) {
            // A granted full-library/individual URI read that is temporarily rejected by the
            // provider is not proof that this imported or externally opened file was removed.
            val fullMediaAccess = uri.authority == MediaStore.AUTHORITY && currentAccess() == LibraryAccess.FULL
            val individualGrant = uri.scheme == ContentResolver.SCHEME_CONTENT &&
                appContext.checkUriPermission(uri, Process.myPid(), Process.myUid(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
            if (failOnProviderFailure && (fullMediaAccess || individualGrant)) {
                throw MediaProviderUnavailableException("文件服务暂时拒绝读取，正在自动重试。", denied)
            }
            return null
        } catch (failure: IOException) {
            if (failOnProviderFailure && failure !is java.io.FileNotFoundException) throw MediaProviderUnavailableException("文件服务暂时不可用。", failure)
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
    }

    private fun animationCandidate(uri: Uri, name: String, mime: String, size: Long, date: Long, inspectGif: Boolean): Boolean {
        if (!isPotentiallyAnimated(name, mime)) return false
        if (!inspectGif && (mime == "image/gif" || name.endsWith(".gif", true))) return true
        val key = "$uri:$size:$date"
        return try {
            val info = headerCache[key] ?: MediaHeader.read(resolver, uri).also {
                if (headerCache.size > 2_048) headerCache.clear()
                headerCache[key] = it
            }
            info.isAnimated || !info.animationKnown
        } catch (_: IOException) {
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun isPotentiallyAnimated(name: String, mime: String): Boolean =
        mime in setOf("image/webp", "image/gif", "image/apng", "image/heif-sequence", "image/avif-sequence") ||
            name.substringAfterLast('.', "").lowercase() in setOf("webp", "gif", "apng")

    private fun copyToPrivateStorage(uri: Uri): Uri {
        var displayName = uri.lastPathSegment.orEmpty().substringAfterLast('/').ifBlank { "image" }
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) displayName = it.string(OpenableColumns.DISPLAY_NAME).ifBlank { displayName }
        }
        val safeName = displayName.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(120)
        val directory = File(appContext.filesDir, "imported").apply { mkdirs() }
        val target = File(directory, "${hash(uri.toString())}_$safeName")
        val partial = File(directory, "${target.name}.partial")
        try {
            resolver.openInputStream(uri)?.use { input ->
                partial.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IOException("无法读取导入的图片。")
            if (target.exists() && !target.delete()) throw IOException("导入文件更新失败。")
            if (!partial.renameTo(target)) throw IOException("导入文件保存失败。")
        } finally {
            partial.delete()
        }
        return Uri.fromFile(target)
    }

    private fun mimeFromName(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: when (name.substringAfterLast('.', "").lowercase()) {
                "svg" -> "image/svg+xml"
                "tif", "tiff" -> "image/tiff"
                "jxl" -> "image/jxl"
                "apng" -> "image/apng"
                else -> "application/octet-stream"
            }

    private fun importDateKey(uri: Uri): String = "import_date_${hash(uri.toString())}"
    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    private fun stableId(value: String): Long = value.fold(-3750763034362895579L) { hash, char ->
        (hash xor char.code.toLong()) * 1099511628211L
    } or Long.MIN_VALUE

    private fun Cursor.string(column: String): String = getColumnIndex(column).let { if (it < 0 || isNull(it)) "" else getString(it).orEmpty() }
    private fun Cursor.long(column: String): Long = getColumnIndex(column).let { if (it < 0 || isNull(it)) 0L else getLong(it) }
    private fun Cursor.int(column: String): Int = getColumnIndex(column).let { if (it < 0 || isNull(it)) 0 else getInt(it) }

    private companion object {
        const val FAVORITES = "favorites"
        const val IMPORTS = "imports"
    }
}
