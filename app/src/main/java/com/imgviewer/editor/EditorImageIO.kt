package com.imgviewer.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.graphics.drawable.toBitmap
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.imgviewer.image.ImageLoaderFactory
import com.imgviewer.image.MediaHeader
import com.imgviewer.image.ExtraImageDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal class EditorSource(
    private val bitmap: Bitmap,
    val originalWidth: Int,
    val originalHeight: Int,
    val animated: Boolean,
    val exportWidth: Int,
    val exportHeight: Int,
    val coilOnly: Boolean = false,
    val rawPreview: Boolean = false
) {
    private val bitmapLock = Any()
    private var readers = 0
    private var disposed = false

    suspend fun <T> withBitmap(block: suspend (Bitmap) -> T): T {
        synchronized(bitmapLock) {
            if (disposed) throw CancellationException("Editor closed")
            readers++
        }
        return try { block(bitmap) } finally {
            synchronized(bitmapLock) {
                readers--
                if (disposed && readers == 0 && !bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    fun dispose() {
        synchronized(bitmapLock) {
            disposed = true
            // Cancellation can race a worker's Canvas read; wait for its borrower to leave.
            if (readers == 0 && !bitmap.isRecycled) bitmap.recycle()
        }
    }
}

internal object EditorImageIO {
    // Leave headroom for two software bitmaps, the viewer, and the PNG encoder on smaller heaps.
    private val exportPixelBudget: Long
        get() = min(16_000_000L, max(2_000_000L, Runtime.getRuntime().maxMemory() / 16L))

    private fun targetSize(width: Int, height: Int, pixelBudget: Long): Pair<Int, Int> {
        val scale = min(1.0, sqrt(pixelBudget.toDouble() / (width.toLong() * height)))
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    private fun isRaw(context: Context, uri: Uri): Boolean {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty().lowercase()
        if (mime in RawMimeTypes) return true
        var name = uri.lastPathSegment.orEmpty()
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0) name = cursor.getString(column).orEmpty().ifBlank { name }
                }
            }
        }
        return name.substringAfterLast('.', "").lowercase() in RawExtensions
    }

    private val RawExtensions = setOf("dng", "cr2", "crw", "nef", "nrw", "arw", "rw2", "orf", "pef", "srw", "raf")
    private val RawMimeTypes = setOf(
        "image/x-adobe-dng", "image/x-canon-cr2", "image/x-canon-crw", "image/x-nikon-nef", "image/x-nikon-nrw",
        "image/x-sony-arw", "image/x-panasonic-rw2", "image/x-olympus-orf", "image/x-pentax-pef",
        "image/x-samsung-srw", "image/x-fuji-raf", "image/x-fujifilm-raf"
    )

    suspend fun load(context: Context, uri: Uri): EditorSource = withContext(Dispatchers.IO) {
        val header = runCatching { MediaHeader.read(context.contentResolver, uri) }.getOrNull()
        var width = 0
        var height = 0
        var animated = header?.isAnimated == true
        val rawPreview = isRaw(context, uri)
        var coilOnly = rawPreview
        val bitmap = if (rawPreview) {
            // Match the viewer's RAW contract: edit the embedded preview, never the sensor raster.
            decodeWithCoil(context, uri, 1600, forceRaw = true).also {
                width = it.width
                height = it.height
            }
        } else try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                width = info.size.width
                height = info.size.height
                animated = animated || info.isAnimated
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                val target = targetSize(width, height, 1_400_000L)
                decoder.setTargetSize(target.first, target.second)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Coil supplies SVG and the platform's additional still-image decoders.
            coilOnly = true
            decodeWithCoil(context, uri, 1600).also {
                // A native header is not a promise that its full pixel data can be decoded.
                width = it.width
                height = it.height
            }
        }
        try {
            currentCoroutineContext().ensureActive()
        } catch (error: CancellationException) {
            bitmap.recycle()
            throw error
        }
        val size = targetSize(width, height, exportPixelBudget)
        EditorSource(bitmap, width, height, animated, size.first, size.second, coilOnly, rawPreview)
    }

    private suspend fun decodeWithCoil(context: Context, uri: Uri, edge: Int, forceRaw: Boolean = false): Bitmap {
        val result = ImageLoaderFactory.create(context).execute(
            ImageRequest.Builder(context).data(uri).size(edge, edge).allowHardware(false)
                .apply { if (forceRaw) decoderFactory(ExtraImageDecoder.Factory(forceRaw = true)) }
                .memoryCachePolicy(coil.request.CachePolicy.DISABLED)
                .diskCachePolicy(coil.request.CachePolicy.DISABLED).build()
        )
        if (result !is SuccessResult) throw IOException("无法解码这张图片，请检查文件是否完整。")
        val drawable = result.drawable
        val raw = drawable.toBitmap(config = Bitmap.Config.ARGB_8888)
        return raw.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IOException("无法创建可编辑的图片。")
    }

    /** Crop and rotation use exactly the same image coordinates for preview and export. */
    internal suspend fun render(
        source: Bitmap,
        rotation: Int,
        crop: CropRect,
        adjustments: EditAdjustments
    ): Bitmap = withContext(Dispatchers.Default) {
        val quarterTurns = ((rotation % 4) + 4) % 4
        val orientedWidth = if (quarterTurns % 2 == 0) source.width else source.height
        val orientedHeight = if (quarterTurns % 2 == 0) source.height else source.width
        val bounds = crop.pixelBounds(orientedWidth, orientedHeight)
        val result = Bitmap.createBitmap(bounds.width, bounds.height, Bitmap.Config.ARGB_8888)
        try {
            val matrix = Matrix().apply {
                when (quarterTurns) {
                    1 -> { setRotate(90f); postTranslate(source.height.toFloat(), 0f) }
                    2 -> { setRotate(180f); postTranslate(source.width.toFloat(), source.height.toFloat()) }
                    3 -> { setRotate(270f); postTranslate(0f, source.width.toFloat()) }
                }
                postTranslate(-bounds.left.toFloat(), -bounds.top.toFloat())
            }
            Canvas(result).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            if (!adjustments.isIdentity) {
                val row = IntArray(result.width)
                val processor = EditColorMath.Processor(adjustments)
                for (y in 0 until result.height) {
                    if (y % 16 == 0) currentCoroutineContext().ensureActive()
                    result.getPixels(row, 0, result.width, 0, y, result.width, 1)
                    for (x in row.indices) row[x] = processor.transform(row[x])
                    result.setPixels(row, 0, result.width, 0, y, result.width, 1)
                }
            }
            currentCoroutineContext().ensureActive()
            result
        } catch (error: Throwable) {
            result.recycle()
            throw error
        }
    }

    suspend fun saveCopy(
        context: Context,
        uri: Uri,
        name: String,
        loaded: EditorSource,
        rotation: Int,
        crop: CropRect,
        adjustments: EditAdjustments
    ): Uri = withContext(Dispatchers.IO) {
        var original: Bitmap? = null
        var edited: Bitmap? = null
        var pending: Uri? = null
        var legacyFile: File? = null
        try {
            val decoded = if (loaded.coilOnly) {
                // Reuse exactly the fallback raster the user edited. Decoder sample-size rounding
                // can otherwise change TIFF / RAW dimensions on a second request.
                loaded.withBitmap { bitmap ->
                    if (bitmap.width == loaded.exportWidth && bitmap.height == loaded.exportHeight)
                        bitmap.copy(Bitmap.Config.ARGB_8888, true)
                            ?: throw IOException("无法创建图片副本。")
                    else Bitmap.createScaledBitmap(bitmap, loaded.exportWidth, loaded.exportHeight, true)
                }
            } else try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                    decoder.setTargetSize(loaded.exportWidth, loaded.exportHeight)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                decodeWithCoil(context, uri, max(loaded.exportWidth, loaded.exportHeight))
            }
            original = decoded
            currentCoroutineContext().ensureActive()
            val outputBitmap = render(decoded, rotation, crop, adjustments)
            edited = outputBitmap
            // The full-resolution source is no longer needed while PNG is compressed.
            original.recycle()
            original = null
            val filename = name.substringBeforeLast('.').take(60).replace(Regex("[^\\p{L}\\p{N}_ -]"), "_")
                .ifBlank { "ImgViewer" } + "_edited_${System.currentTimeMillis()}.png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000L)
                put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                put(MediaStore.Images.Media.WIDTH, outputBitmap.width)
                put(MediaStore.Images.Media.HEIGHT, outputBitmap.height)
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ImgViewer")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                } else {
                    @Suppress("DEPRECATION")
                    val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ImgViewer")
                    if (!directory.exists() && !directory.mkdirs()) throw IOException("无法创建 ImgViewer 图集，请检查存储权限。")
                    legacyFile = File(directory, filename)
                    @Suppress("DEPRECATION")
                    put(MediaStore.Images.Media.DATA, legacyFile!!.absolutePath)
                }
            }
            val resolver = context.contentResolver
            val collection = if (Build.VERSION.SDK_INT >= 29)
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val output = resolver.insert(collection, values) ?: throw IOException("无法创建图片副本。")
            pending = output
            resolver.openOutputStream(output, "w")?.use { stream ->
                if (!outputBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw IOException("图片编码失败。")
                stream.flush()
            } ?: throw IOException("无法写入图片副本。")
            currentCoroutineContext().ensureActive()
            if (Build.VERSION.SDK_INT >= 29) {
                val published = resolver.update(output, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
                if (published != 1) throw IOException("图片副本未能发布，请重试。")
            }
            pending = null
            output
        } catch (error: Throwable) {
            // Neither failed nor cancelled exports leave an unfinished photo in the library.
            pending?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            legacyFile?.let { runCatching { it.delete() } }
            throw error
        } finally {
            original?.recycle()
            edited?.recycle()
        }
    }
}
