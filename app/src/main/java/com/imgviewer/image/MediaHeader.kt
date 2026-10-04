package com.imgviewer.image

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Bounded, signature-based inspection. Extensions never decide whether a WebP is animated. */
object MediaHeader {
    enum class Format(val mimeType: String) {
        JPEG("image/jpeg"), PNG("image/png"), WEBP("image/webp"), GIF("image/gif"),
        BMP("image/bmp"), SVG("image/svg+xml"), HEIF("image/heif"), AVIF("image/avif"),
        TIFF("image/tiff"), ICO("image/x-icon"), JXL("image/jxl"), UNKNOWN("application/octet-stream"),
    }

    data class Info(
        val format: Format = Format.UNKNOWN,
        val isAnimated: Boolean = false,
        val animationKnown: Boolean = true,
        val width: Int = 0,
        val height: Int = 0,
    )

    /** Call on an IO dispatcher. Large GIFs may be candidates until the decoder confirms animation. */
    fun read(resolver: ContentResolver, uri: Uri, maxBytes: Int = 1_048_576): Info =
        resolver.openInputStream(uri)?.use { inspect(readPrefix(it, maxBytes)) } ?: Info()

    fun inspect(bytes: ByteArray): Info {
        if (bytes.has(0, "RIFF") && bytes.has(8, "WEBP")) return inspectWebP(bytes)
        if (bytes.has(0, "GIF87a") || bytes.has(0, "GIF89a")) return inspectGif(bytes)
        if (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) {
            return inspectPng(bytes)
        }
        if (bytes.size >= 3 && bytes.u8(0) == 0xff && bytes.u8(1) == 0xd8 && bytes.u8(2) == 0xff) {
            return Info(Format.JPEG)
        }
        if (bytes.has(0, "BM")) return Info(Format.BMP)
        if (bytes.has(0, "II*\u0000") || bytes.has(0, "MM\u0000*")) return Info(Format.TIFF)
        if (bytes.size >= 4 && bytes.u8(0) == 0 && bytes.u8(1) == 0 &&
            bytes.u8(2) == 1 && bytes.u8(3) == 0) return Info(Format.ICO)
        if ((bytes.size >= 2 && bytes.u8(0) == 0xff && bytes.u8(1) == 0x0a) ||
            bytes.has(4, "JXL ")) return Info(Format.JXL)
        if (bytes.has(4, "ftyp")) {
            val boxEnd = minOf(bytes.be32(0), bytes.size.toLong(), 128L).toInt()
            val brandOffsets = listOf(8) + (16 until boxEnd step 4)
            val brands = brandOffsets.mapNotNull { offset ->
                if (offset + 4 <= bytes.size) bytes.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII)
                else null
            }
            if (brands.any { it == "avif" || it == "avis" }) {
                return Info(Format.AVIF, isAnimated = "avis" in brands)
            }
            if (brands.any { it in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1", "heis", "hevm") }) {
                return Info(Format.HEIF, isAnimated = brands.any { it in setOf("hevc", "hevx", "msf1", "hevm") })
            }
        }
        val text = bytes.copyOfRange(0, minOf(bytes.size, 8_192)).toString(Charsets.UTF_8)
            .trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (Regex("<svg(?:\\s|>)", RegexOption.IGNORE_CASE).containsMatchIn(text)) return Info(Format.SVG)
        return Info()
    }

    private fun inspectWebP(bytes: ByteArray): Info {
        // VP8X is the first chunk in an extended WebP. Its animation flag is bit 1.
        if (bytes.has(12, "VP8X")) {
            if (bytes.size >= 30 && bytes.le32(16) >= 10L) {
                return Info(
                    Format.WEBP, isAnimated = bytes.u8(20) and 0x02 != 0,
                    width = bytes.le24(24) + 1, height = bytes.le24(27) + 1,
                )
            }
            return Info(Format.WEBP, animationKnown = false)
        }
        return Info(Format.WEBP, animationKnown = bytes.size >= 20)
    }

    private fun inspectPng(bytes: ByteArray): Info {
        var position = 8
        var width = 0
        var height = 0
        while (position + 12 <= bytes.size) {
            val length = bytes.be32(position)
            val typeOffset = position + 4
            if (bytes.has(typeOffset, "IHDR") && length >= 8 && position + 16 <= bytes.size) {
                width = bytes.be32(position + 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                height = bytes.be32(position + 12).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
            if (bytes.has(typeOffset, "acTL") && length >= 8 && position + 16 <= bytes.size) {
                return Info(Format.PNG, isAnimated = bytes.be32(position + 8) > 1, width = width, height = height)
            }
            if (bytes.has(typeOffset, "IDAT") || bytes.has(typeOffset, "IEND")) {
                return Info(Format.PNG, width = width, height = height)
            }
            val next = position.toLong() + 12L + length
            if (next > bytes.size || next > Int.MAX_VALUE) {
                return Info(Format.PNG, animationKnown = false, width = width, height = height)
            }
            position = next.toInt()
        }
        return Info(Format.PNG, animationKnown = false, width = width, height = height)
    }

    private fun inspectGif(bytes: ByteArray): Info {
        if (bytes.size < 13) return Info(Format.GIF, animationKnown = false)
        val width = bytes.le16(6)
        val height = bytes.le16(8)
        var position = 13
        if (bytes.u8(10) and 0x80 != 0) position += 3 * (1 shl ((bytes.u8(10) and 7) + 1))
        var frames = 0
        while (position < bytes.size) {
            when (bytes.u8(position++)) {
                0x3b -> return Info(Format.GIF, frames > 1, width = width, height = height)
                0x21 -> {
                    if (position >= bytes.size) break
                    position++ // Extension label; payload is length-prefixed subblocks.
                    position = skipGifSubblocks(bytes, position) ?: break
                }
                0x2c -> {
                    if (position + 9 > bytes.size) break
                    val packed = bytes.u8(position + 8)
                    position += 9
                    if (packed and 0x80 != 0) position += 3 * (1 shl ((packed and 7) + 1))
                    if (position >= bytes.size) break
                    position++ // LZW minimum code size.
                    position = skipGifSubblocks(bytes, position) ?: break
                    frames++
                    if (frames > 1) return Info(Format.GIF, true, width = width, height = height)
                }
                else -> break
            }
        }
        return Info(Format.GIF, animationKnown = false, width = width, height = height)
    }

    private fun skipGifSubblocks(bytes: ByteArray, start: Int): Int? {
        var position = start
        while (position < bytes.size) {
            val size = bytes.u8(position++)
            if (size == 0) return position
            if (position + size > bytes.size) return null
            position += size
        }
        return null
    }

    private fun readPrefix(stream: InputStream, limit: Int): ByteArray {
        require(limit > 0)
        val out = ByteArrayOutputStream(minOf(limit, 8_192))
        val buffer = ByteArray(minOf(limit, 8_192))
        var checkWebP = true
        while (out.size() < limit) {
            val count = stream.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (count <= 0) break
            out.write(buffer, 0, count)
            // A WebP animation flag fits in 30 bytes; never read its compressed frames for a badge.
            if (checkWebP && out.size() >= 32) {
                checkWebP = false
                if (out.toByteArray().let { it.has(0, "RIFF") && it.has(8, "WEBP") }) break
            }
        }
        return out.toByteArray()
    }

    private fun ByteArray.has(offset: Int, value: String): Boolean =
        offset >= 0 && offset + value.length <= size && value.indices.all { u8(offset + it) == value[it].code }
    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xff
    private fun ByteArray.le16(index: Int): Int = u8(index) or (u8(index + 1) shl 8)
    private fun ByteArray.le24(index: Int): Int = le16(index) or (u8(index + 2) shl 16)
    private fun ByteArray.le32(index: Int): Long = (0..3).sumOf { u8(index + it).toLong() shl (it * 8) }
    private fun ByteArray.be32(index: Int): Long = (0..3).sumOf { u8(index + it).toLong() shl ((3 - it) * 8) }
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
}
