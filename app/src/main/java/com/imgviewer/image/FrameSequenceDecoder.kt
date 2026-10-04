package com.imgviewer.image

import android.graphics.drawable.BitmapDrawable
import coil.ImageLoader
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.fetch.SourceResult
import coil.request.Options
import coil.size.Dimension
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.ByteArrayInputStream
import androidx.exifinterface.media.ExifInterface
import kotlin.math.sqrt

/** Static frame bitstreams use Android decoders; the application's frame clock can truly pause. */
internal class FrameSequenceDecoder(private val result: SourceResult, private val options: Options) : Decoder {
    override suspend fun decode(): DecodeResult {
        // Reading can briefly hold the growing buffer, its old allocation and the final copy.
        // Reserve space for adjacent pager pages, thumbnail cache and the frame compositor too.
        val maxEncodedBytes = minOf(MAX_ENCODED_BYTES, Runtime.getRuntime().maxMemory() / 12)
        val bytes = result.source.source().inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size().toLong() + count > maxEncodedBytes)
                    throw IOException("动画图片超过 ${maxEncodedBytes / (1_024 * 1_024)} MB，请压缩后查看。")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val animation = AnimationFrames.parse(bytes)
        val requestedEdge = maxOf(
            (options.size.width as? Dimension.Pixels)?.px ?: 2_048,
            (options.size.height as? Dimension.Pixels)?.px ?: 2_048,
        ).coerceIn(64, 2_048)
        // Four pixel buffers (canvas, optional disposal-3 backup, visible and prefetched frame)
        // fit inside 1/16 of the heap per page; the pager can retain adjacent decoded pages.
        // The encoded file and temporary frame bitstream are bounded separately.
        val maxEdge = minOf(requestedEdge, sqrt(Runtime.getRuntime().maxMemory() / 256.0).toInt().coerceIn(128, 2_048))
        val compositor = AnimationCompositor(animation, maxEdge)
        try {
            val first = compositor.nextFrame()
            if (animation.frames.size == 1) {
                compositor.close()
                return DecodeResult(BitmapDrawable(options.context.resources, first.bitmap), true)
            }
            val orientation = try {
                ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
            } catch (_: IOException) { 1 }
            return DecodeResult(FrameSequenceDrawable(compositor, first, orientation), true)
        } catch (error: Throwable) { compositor.close(); throw error }
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val source = result.source.source()
            source.request(32)
            val prefix = source.buffer.clone().readByteArray(minOf(32, source.buffer.size))
            val header = MediaHeader.inspect(prefix)
            return if (header.format == MediaHeader.Format.GIF || (header.format == MediaHeader.Format.WEBP && header.isAnimated))
                FrameSequenceDecoder(result, options) else null
        }
    }

    private companion object { const val MAX_ENCODED_BYTES = 64L * 1_024 * 1_024 }
}
