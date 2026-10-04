package com.imgviewer.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import androidx.exifinterface.media.ExifInterface
import coil.ImageLoader
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.fetch.SourceResult
import coil.request.Options
import coil.size.Dimension
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Kotlin-only TIFF / ICO decoding plus embedded JPEG previews for camera RAW files. */
internal class ExtraImageDecoder(
    private val result: SourceResult,
    private val options: Options,
    private val format: MediaHeader.Format,
    private val raw: Boolean,
) : Decoder {
    override suspend fun decode(): DecodeResult {
        val bytes = result.source.source().inputStream().use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size().toLong() + count > MAX_ENCODED_BYTES) {
                    throw RasterCodecs.Unsupported("TIFF / ICO / RAW 文件超过 32 MB 安全解码范围，请转换后查看。")
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val maxEdge = maxOf(
            (options.size.width as? Dimension.Pixels)?.px ?: 2_048,
            (options.size.height as? Dimension.Pixels)?.px ?: 2_048,
        ).coerceIn(64, 2_048)
        val bitmap = when {
            raw -> rawPreview(bytes, maxEdge)
            format == MediaHeader.Format.TIFF -> {
                try { rasterBitmap(RasterCodecs.tiff(bytes, maxEdge)) }
                catch (unsupported: RasterCodecs.Unsupported) {
                    // TIFF-based camera files often expose a preview even when the Bayer data isn't supported.
                    try { rawPreview(bytes, maxEdge) } catch (_: IOException) { throw unsupported }
                }
            }
            else -> decodeIcon(bytes)
        }
        return DecodeResult(BitmapDrawable(options.context.resources, bitmap), isSampled = true)
    }

    private fun rawPreview(bytes: ByteArray, maxEdge: Int): Bitmap {
        val exif = ExifInterface(ByteArrayInputStream(bytes))
        val compressedPreview = exif.thumbnail
        val thumbnail = if (compressedPreview != null) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(compressedPreview, 0, compressedPreview.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw RasterCodecs.Unsupported("RAW 内嵌预览数据损坏。")
            }
            var sampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > maxEdge) sampleSize *= 2
            BitmapFactory.decodeByteArray(compressedPreview, 0, compressedPreview.size,
                BitmapFactory.Options().apply { inSampleSize = sampleSize })
        } else {
            val width = exif.getAttributeInt(ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH, 0)
            val height = exif.getAttributeInt(ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH, 0)
            if (width !in 1..2_048 || height !in 1..2_048) {
                throw RasterCodecs.Unsupported("RAW 没有尺寸安全的内嵌预览；ImgViewer 不处理完整 RAW 数据。")
            }
            exif.thumbnailBitmap
        } ?: throw RasterCodecs.Unsupported("这张 RAW 文件没有可用的内嵌预览；ImgViewer 不处理完整 RAW 数据。")
        val matrix = Matrix()
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        }
        if (matrix.isIdentity) return thumbnail
        return Bitmap.createBitmap(thumbnail, 0, 0, thumbnail.width, thumbnail.height, matrix, true).also {
            if (it !== thumbnail) thumbnail.recycle()
        }
    }

    private fun decodeIcon(bytes: ByteArray): Bitmap {
        var lastError: IOException? = null
        for (image in RasterCodecs.iconEntries(bytes)) {
            try {
                if (MediaHeader.inspect(image).format == MediaHeader.Format.PNG) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
                    if (bounds.outWidth !in 1..2_048 || bounds.outHeight !in 1..2_048) {
                        throw RasterCodecs.Unsupported("ICO 图像尺寸超出范围。")
                    }
                    return BitmapFactory.decodeByteArray(image, 0, image.size)
                        ?: throw RasterCodecs.Unsupported("ICO PNG 图像损坏。")
                }
                return rasterBitmap(RasterCodecs.iconDib(image))
            } catch (error: IOException) { lastError = error }
        }
        throw lastError ?: RasterCodecs.Unsupported("ICO 中没有支持的 PNG 或普通 DIB 图像。")
    }

    private fun rasterBitmap(raster: RasterCodecs.Raster): Bitmap = Bitmap.createBitmap(
        raster.pixels, raster.width, raster.height, Bitmap.Config.ARGB_8888)

    class Factory(private val forceRaw: Boolean = false) : Decoder.Factory {
        override fun create(result: SourceResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val source = result.source.source()
            source.request(32)
            val prefix = source.buffer.clone().readByteArray(minOf(32, source.buffer.size))
            val format = MediaHeader.inspect(prefix).format
            val mime = result.mimeType.orEmpty().lowercase()
            val raw = forceRaw || mime in RAW_MIME_TYPES || prefix.toString(Charsets.US_ASCII).startsWith("FUJIFILMCCD-RAW")
            if (raw || format in setOf(MediaHeader.Format.TIFF, MediaHeader.Format.ICO)) {
                return ExtraImageDecoder(result, options, format, raw)
            }
            return null
        }
    }

    private companion object {
        const val MAX_ENCODED_BYTES = 32L * 1_024 * 1_024
        val RAW_MIME_TYPES = setOf(
            "image/x-adobe-dng", "image/x-canon-cr2", "image/x-canon-crw", "image/x-nikon-nef", "image/x-nikon-nrw",
            "image/x-sony-arw", "image/x-panasonic-rw2", "image/x-olympus-orf", "image/x-pentax-pef",
            "image/x-samsung-srw", "image/x-fuji-raf", "image/x-fujifilm-raf",
        )
    }
}
