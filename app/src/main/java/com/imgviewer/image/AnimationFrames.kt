package com.imgviewer.image

import java.io.IOException

/** Encoded frame metadata; image pixels continue to be decoded by Android, not a Kotlin codec. */
internal class AnimationFrames(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val background: Int,
    val frames: List<Frame>,
    private val globalPalette: ByteArray? = null,
    private val globalPacked: Int = 0,
    private val iccProfile: ByteArray? = null,
) {
    internal data class Frame(
        val x: Int, val y: Int, val width: Int, val height: Int,
        val durationMillis: Int,
        val disposal: Int,
        val blend: Boolean,
        val offset: Int,
        val length: Int,
        val gif: Boolean = false,
        val transparentIndex: Int = -1,
        val packed: Int = 0,
        val localPalette: ByteArray? = null,
        val hasAlpha: Boolean = false,
    )

    fun encodedFrame(frame: Frame): ByteArray {
        val profileLength = iccProfile?.size ?: 0
        val profileChunkLength = if (iccProfile != null) 8 + profileLength + (profileLength and 1) else 0
        val length = if (frame.gif) 24 + (globalPalette?.size ?: 0) + (frame.localPalette?.size ?: 0) +
            (if (frame.transparentIndex >= 0) 8 else 0) + frame.length else 30 + profileChunkLength + frame.length
        return FrameBytesWriter(length).run {
        if (frame.gif) {
            write("GIF89a".toByteArray(Charsets.US_ASCII))
            little(frame.width, 2); little(frame.height, 2)
            write(globalPacked); write(0); write(0)
            globalPalette?.let(::write)
            if (frame.transparentIndex >= 0) {
                write(byteArrayOf(0x21, 0xF9.toByte(), 4, 1, 0, 0, frame.transparentIndex.toByte(), 0))
            }
            write(0x2C); little(0, 2); little(0, 2)
            little(frame.width, 2); little(frame.height, 2); write(frame.packed)
            frame.localPalette?.let(::write)
            write(bytes, frame.offset, frame.length)
            write(0x3B)
        } else {
            // ANMF contains ordinary ALPH/VP8/VP8L chunks. Wrap one frame in a static WebP file.
            // VP8X supplies alpha and ICC support even when a frame uses lossy VP8 plus ALPH.
            write("RIFF".toByteArray(Charsets.US_ASCII)); little(4 + 18 + profileChunkLength + frame.length, 4)
            write("WEBPVP8X".toByteArray(Charsets.US_ASCII)); little(10, 4)
            write((if (iccProfile != null) 0x20 else 0) or (if (frame.hasAlpha) 0x10 else 0)); write(byteArrayOf(0, 0, 0))
            little(frame.width - 1, 3); little(frame.height - 1, 3)
            if (iccProfile != null) {
                write("ICCP".toByteArray(Charsets.US_ASCII)); little(profileLength, 4)
                write(iccProfile); if (profileLength and 1 != 0) write(0)
            }
            write(bytes, frame.offset, frame.length)
        }
        toByteArray()
        }
    }

    companion object {
        private const val MAX_FRAMES = 20_000

        fun parse(bytes: ByteArray): AnimationFrames = when {
            bytes.tag(0, "GIF87a") || bytes.tag(0, "GIF89a") -> parseGif(bytes)
            bytes.tag(0, "RIFF") && bytes.tag(8, "WEBP") -> parseWebp(bytes)
            else -> invalid("不是受支持的动画图片。")
        }

        // https://developers.google.com/speed/webp/docs/riff_container#animation
        private fun parseWebp(bytes: ByteArray): AnimationFrames {
            bytes.requireRange(0, 12)
            val declaredLength = bytes.uint(4, 4) + 8L
            if (declaredLength > bytes.size || declaredLength < 12) invalid("WebP 文件不完整。")
            var width = 0; var height = 0; var background = 0
            var hasAnimation = false
            var profile: ByteArray? = null
            val frames = ArrayList<Frame>()
            var cursor = 12
            while (cursor.toLong() + 8 <= declaredLength) {
                val length = bytes.uint(cursor + 4, 4)
                val end = cursor.toLong() + 8 + length
                if (length > Int.MAX_VALUE || end > declaredLength) invalid("WebP 数据块损坏。")
                val start = cursor + 8
                when {
                    bytes.tag(cursor, "VP8X") -> {
                        if (length < 10) invalid("WebP 扩展头损坏。")
                        hasAnimation = bytes.u8(start) and 2 != 0
                        width = bytes.uint(start + 4, 3).toInt() + 1
                        height = bytes.uint(start + 7, 3).toInt() + 1
                    }
                    bytes.tag(cursor, "ICCP") -> profile = bytes.copyOfRange(start, end.toInt())
                    bytes.tag(cursor, "ANIM") -> {
                        if (length < 6) invalid("WebP 动画头损坏。")
                        background = bytes.uint(start, 4).toInt()
                    }
                    bytes.tag(cursor, "ANMF") -> {
                        if (length < 24 || frames.size >= MAX_FRAMES) invalid("WebP 动画帧损坏或过多。")
                        val flags = bytes.u8(start + 15)
                        frames += Frame(
                            x = bytes.uint(start, 3).toInt() * 2,
                            y = bytes.uint(start + 3, 3).toInt() * 2,
                            width = bytes.uint(start + 6, 3).toInt() + 1,
                            height = bytes.uint(start + 9, 3).toInt() + 1,
                            durationMillis = bytes.uint(start + 12, 3).toInt().coerceAtLeast(10),
                            disposal = if (flags and 1 != 0) 2 else 1,
                            blend = flags and 2 == 0,
                            offset = start + 16,
                            length = length.toInt() - 16,
                            hasAlpha = bytes.tag(start + 16, "ALPH") ||
                                (bytes.tag(start + 16, "VP8L") && length >= 29 && bytes.u8(start + 28) and 0x10 != 0),
                        )
                    }
                }
                cursor = (end + (length and 1)).toInt()
            }
            if (!hasAnimation || frames.isEmpty()) invalid("WebP 动画没有可用的帧。")
            validateCanvas(width, height, frames)
            return AnimationFrames(bytes, width, height, background, frames, iccProfile = profile)
        }

        // https://www.w3.org/Graphics/GIF/spec-gif89a.txt
        private fun parseGif(bytes: ByteArray): AnimationFrames {
            bytes.requireRange(0, 13)
            val width = bytes.uint(6, 2).toInt(); val height = bytes.uint(8, 2).toInt()
            val packed = bytes.u8(10)
            val backgroundIndex = bytes.u8(11)
            var cursor = 13
            val palette = if (packed and 0x80 != 0) {
                val size = 3 * (1 shl ((packed and 7) + 1))
                bytes.requireRange(cursor, size)
                bytes.copyOfRange(cursor, cursor + size).also { cursor += size }
            } else null
            val frames = ArrayList<Frame>()
            var duration = 100; var disposal = 1; var transparent = -1
            while (cursor < bytes.size) {
                when (bytes.u8(cursor++)) {
                    0x3B -> break
                    0x21 -> {
                        bytes.requireRange(cursor, 1)
                        val label = bytes.u8(cursor++)
                        if (label == 0xF9) {
                            bytes.requireRange(cursor, 6)
                            if (bytes.u8(cursor) != 4 || bytes.u8(cursor + 5) != 0) invalid("GIF 帧控制块损坏。")
                            val flags = bytes.u8(cursor + 1)
                            val delay = bytes.uint(cursor + 2, 2).toInt() * 10
                            duration = if (delay <= 10) 100 else delay
                            disposal = ((flags shr 2) and 7).takeIf { it in 1..3 } ?: 1
                            transparent = if (flags and 1 != 0) bytes.u8(cursor + 4) else -1
                            cursor += 6
                        } else cursor = bytes.skipSubBlocks(cursor)
                    }
                    0x2C -> {
                        if (frames.size >= MAX_FRAMES) invalid("GIF 动画帧过多。")
                        bytes.requireRange(cursor, 9)
                        val x = bytes.uint(cursor, 2).toInt(); val y = bytes.uint(cursor + 2, 2).toInt()
                        val frameWidth = bytes.uint(cursor + 4, 2).toInt(); val frameHeight = bytes.uint(cursor + 6, 2).toInt()
                        val framePacked = bytes.u8(cursor + 8)
                        cursor += 9
                        val local = if (framePacked and 0x80 != 0) {
                            val size = 3 * (1 shl ((framePacked and 7) + 1))
                            bytes.requireRange(cursor, size)
                            bytes.copyOfRange(cursor, cursor + size).also { cursor += size }
                        } else null
                        bytes.requireRange(cursor, 1)
                        val imageStart = cursor++ // Includes the LZW minimum code size.
                        cursor = bytes.skipSubBlocks(cursor)
                        frames += Frame(x, y, frameWidth, frameHeight, duration, disposal, true,
                            imageStart, cursor - imageStart, true, transparent, framePacked, local)
                        duration = 100; disposal = 1; transparent = -1
                    }
                    else -> invalid("GIF 数据块损坏。")
                }
            }
            if (frames.isEmpty()) invalid("GIF 没有可用的帧。")
            validateCanvas(width, height, frames)
            val background = if (frames.first().transparentIndex >= 0 || palette == null || backgroundIndex * 3 + 2 >= palette.size) 0
                else (0xFF shl 24) or ((palette[backgroundIndex * 3].toInt() and 255) shl 16) or
                    ((palette[backgroundIndex * 3 + 1].toInt() and 255) shl 8) or (palette[backgroundIndex * 3 + 2].toInt() and 255)
            return AnimationFrames(bytes, width, height, background, frames, palette, packed)
        }

        private fun validateCanvas(width: Int, height: Int, frames: List<Frame>) {
            if (width <= 0 || height <= 0) invalid("动画尺寸无效。")
            frames.forEach { frame ->
                if (frame.width <= 0 || frame.height <= 0 || frame.x < 0 || frame.y < 0 ||
                    frame.x.toLong() + frame.width > width || frame.y.toLong() + frame.height > height)
                    invalid("动画帧超出画布。")
            }
        }

        private fun ByteArray.skipSubBlocks(from: Int): Int {
            var cursor = from
            while (true) {
                requireRange(cursor, 1)
                val size = u8(cursor++)
                if (size == 0) return cursor
                requireRange(cursor, size)
                cursor += size
            }
        }
        private fun ByteArray.tag(offset: Int, tag: String): Boolean = offset >= 0 && offset.toLong() + tag.length <= size &&
            tag.indices.all { this[offset + it].toInt() and 255 == tag[it].code }
        private fun ByteArray.u8(offset: Int): Int { requireRange(offset, 1); return this[offset].toInt() and 255 }
        private fun ByteArray.uint(offset: Int, count: Int): Long {
            requireRange(offset, count)
            var result = 0L
            repeat(count) { result = result or (u8(offset + it).toLong() shl (it * 8)) }
            return result
        }
        private fun ByteArray.requireRange(offset: Int, count: Int) {
            if (offset < 0 || count < 0 || offset.toLong() + count > size) invalid("动画文件不完整。")
        }
        private fun invalid(message: String): Nothing = throw IOException(message)
    }
}

/** Allocate a frame's encoded bytes once; ByteArrayOutputStream.toByteArray would duplicate it. */
private class FrameBytesWriter(size: Int) {
    private val output = ByteArray(size)
    private var cursor = 0
    fun write(value: Int) { output[cursor++] = value.toByte() }
    fun write(bytes: ByteArray) = write(bytes, 0, bytes.size)
    fun write(bytes: ByteArray, offset: Int, length: Int) {
        bytes.copyInto(output, cursor, offset, offset + length)
        cursor += length
    }
    fun little(value: Int, count: Int) { repeat(count) { write(value ushr (8 * it) and 255) } }
    fun toByteArray(): ByteArray { check(cursor == output.size); return output }
}
