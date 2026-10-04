package com.imgviewer.image

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.InflaterInputStream
import kotlin.math.ceil

/** Small Android-independent codecs. TIFF displays the first page; camera RAW is handled separately. */
internal object RasterCodecs {
    data class Raster(val width: Int, val height: Int, val pixels: IntArray)
    class Unsupported(message: String) : IOException(message)
    private const val MAX_STRIP_BYTES = 32 * 1_024 * 1_024

    fun tiff(bytes: ByteArray, maxEdge: Int = 2_048): Raster {
        require(maxEdge in 1..4_096)
        val littleEndian = bytes.size >= 4 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte()
        val reader = Reader(bytes, littleEndian)
        requireImage(reader.u16(2) == 42, "仅支持标准 TIFF，不支持 BigTIFF。")
        val ifd = reader.u32(4).checkedInt()
        val entries = reader.u16(ifd)
        requireImage(entries <= 1_024, "TIFF 目录过大。")
        reader.check(ifd + 2, entries * 12)
        val tags = mutableMapOf<Int, IntArray>()
        val wanted = setOf(256, 257, 258, 259, 262, 273, 274, 277, 278, 279, 284, 317, 320, 338, 339)
        repeat(entries) { index ->
            val offset = ifd + 2 + index * 12
            val tag = reader.u16(offset)
            if (tag in wanted) {
                val type = reader.u16(offset + 2)
                val count = reader.u32(offset + 4).checkedInt()
                requireImage(count <= 65_536, "TIFF 标签数量过大。")
                val unit = when (type) { 1, 7 -> 1; 3 -> 2; 4 -> 4; else -> 0 }
                if (unit > 0) {
                    val length = count * unit
                    val valuesOffset = if (length <= 4) offset + 8 else reader.u32(offset + 8).checkedInt()
                    reader.check(valuesOffset, length)
                    tags[tag] = IntArray(count) { item ->
                        when (unit) {
                            1 -> reader.u8(valuesOffset + item)
                            2 -> reader.u16(valuesOffset + item * 2)
                            else -> reader.u32(valuesOffset + item * 4).checkedInt()
                        }
                    }
                }
            }
        }
        fun scalar(tag: Int, default: Int = 0): Int = tags[tag]?.firstOrNull() ?: default
        val width = scalar(256)
        val height = scalar(257)
        requireImage(width in 1..65_535 && height in 1..65_535 && width.toLong() * height <= 100_000_000,
            "TIFF 尺寸超出安全解码范围。")
        val compression = scalar(259, 1)
        requireImage(compression in setOf(1, 5, 8, 32946, 32773), "暂不支持此 TIFF 压缩方式。")
        val photometric = scalar(262, 1)
        val samples = scalar(277, 1)
        val bits = tags[258] ?: intArrayOf(1)
        val bitDepth = bits.firstOrNull() ?: 1
        requireImage(bits.all { it == bitDepth }, "暂不支持通道位深不同的 TIFF。")
        requireImage(scalar(284, 1) == 1 && scalar(339, 1) == 1, "暂不支持平面分离或浮点 TIFF。")
        requireImage((photometric == 2 && samples in 3..4 && bitDepth == 8) ||
            (photometric in setOf(0, 1, 3) && samples == 1 && bitDepth in setOf(1, 2, 4, 8)),
            "TIFF 支持 8 位 RGB/RGBA、1/2/4/8 位灰度或索引色；此文件可能是 RAW。")
        val predictor = scalar(317, 1)
        requireImage(predictor == 1 || (predictor == 2 && bitDepth == 8), "暂不支持此 TIFF 预测编码。")
        val offsets = tags[273] ?: throw Unsupported("暂不支持分块 TIFF。")
        val counts = tags[279] ?: throw Unsupported("TIFF 缺少图像条带长度。")
        val rowsPerStrip = scalar(278, height).coerceAtMost(height)
        requireImage(rowsPerStrip > 0 && offsets.size == counts.size, "TIFF 条带目录损坏。")
        val requiredStrips = (height + rowsPerStrip - 1) / rowsPerStrip
        requireImage(offsets.size >= requiredStrips, "TIFF 图像条带不完整。")
        val bytesPerRow = ((width.toLong() * samples * bitDepth + 7) / 8).toInt()
        val sample = ceil(maxOf(width, height).toDouble() / maxEdge).toInt().coerceAtLeast(1)
        val outWidth = (width + sample - 1) / sample
        val outHeight = (height + sample - 1) / sample
        val pixels = IntArray(outWidth * outHeight)
        val palette = tags[320]
        val paletteSize = 1 shl bitDepth
        if (photometric == 3) requireImage(palette != null && palette.size >= paletteSize * 3, "TIFF 索引色表损坏。")
        val associatedAlpha = scalar(338) == 1
        repeat(requiredStrips) { strip ->
            val firstRow = strip * rowsPerStrip
            val rows = minOf(rowsPerStrip, height - firstRow)
            val expectedSize = rows.toLong() * bytesPerRow
            requireImage(expectedSize <= MAX_STRIP_BYTES, "TIFF 单条带过大，请先转换或缩小图片。")
            reader.check(offsets[strip], counts[strip])
            val compressed = bytes.copyOfRange(offsets[strip], offsets[strip] + counts[strip])
            val decoded = when (compression) {
                1 -> { requireImage(compressed.size >= expectedSize, "TIFF 条带数据不完整。"); compressed }
                32773 -> packBits(compressed, expectedSize.toInt())
                5 -> lzw(compressed, expectedSize.toInt())
                else -> inflate(compressed, expectedSize.toInt())
            }
            if (predictor == 2) for (row in 0 until rows) {
                val start = row * bytesPerRow
                for (byte in samples until bytesPerRow) {
                    decoded[start + byte] = ((decoded[start + byte].toInt() and 255) +
                        (decoded[start + byte - samples].toInt() and 255)).toByte()
                }
            }
            for (row in 0 until rows) {
                val y = firstRow + row
                if (y % sample != 0) continue
                val rowOffset = row * bytesPerRow
                for (outX in 0 until outWidth) {
                    val x = outX * sample
                    val color = if (photometric == 2) {
                        val pixelOffset = rowOffset + x * samples
                        val alpha = if (samples == 4) decoded[pixelOffset + 3].toInt() and 255 else 255
                        fun channel(index: Int): Int {
                            val value = decoded[pixelOffset + index].toInt() and 255
                            return if (associatedAlpha && alpha in 1..254) (value * 255 / alpha).coerceAtMost(255) else value
                        }
                        argb(alpha, channel(0), channel(1), channel(2))
                    } else {
                        val bitOffset = x * bitDepth
                        var value = (decoded[rowOffset + bitOffset / 8].toInt() ushr (8 - bitDepth - bitOffset % 8)) and (paletteSize - 1)
                        if (photometric == 3) {
                            argb(255, palette!![value] / 257, palette[paletteSize + value] / 257, palette[paletteSize * 2 + value] / 257)
                        } else {
                            if (photometric == 0) value = paletteSize - 1 - value
                            val gray = value * 255 / (paletteSize - 1)
                            argb(255, gray, gray, gray)
                        }
                    }
                    pixels[(y / sample) * outWidth + outX] = color
                }
            }
        }
        return orient(Raster(outWidth, outHeight, pixels), scalar(274, 1))
    }

    /** Decode classic uncompressed ICO DIB entries. Embedded PNG entries use Android's PNG decoder. */
    fun iconDib(bytes: ByteArray): Raster {
        val reader = Reader(bytes, true)
        val header = reader.u32(0).checkedInt()
        requireImage(header in 40..124, "暂不支持此 ICO 位图头。")
        val width = reader.u32(4).checkedInt()
        val doubledHeight = reader.u32(8).checkedInt()
        val height = doubledHeight / 2
        requireImage(width in 1..2_048 && height in 1..2_048 && doubledHeight % 2 == 0, "ICO 尺寸不正确。")
        val depth = reader.u16(14)
        requireImage(reader.u32(16) == 0L && depth in setOf(1, 4, 8, 24, 32), "暂不支持压缩或位域 ICO。")
        val paletteSize = if (depth <= 8) reader.u32(32).checkedInt().takeIf { it != 0 } ?: (1 shl depth) else 0
        requireImage(paletteSize <= 256, "ICO 调色板过大。")
        val pixelOffset = header + paletteSize * 4
        val stride = ((width * depth + 31) / 32) * 4
        reader.check(pixelOffset, stride * height)
        val maskOffset = pixelOffset + stride * height
        val maskStride = ((width + 31) / 32) * 4
        val hasMask = maskOffset.toLong() + maskStride.toLong() * height <= bytes.size
        val pixels = IntArray(width * height)
        var hasNonzeroAlpha = false
        for (y in 0 until height) for (x in 0 until width) {
            val rowOffset = pixelOffset + (height - 1 - y) * stride
            val color = if (depth >= 24) {
                val offset = rowOffset + x * (depth / 8)
                val alpha = if (depth == 32) reader.u8(offset + 3) else 255
                if (alpha > 0) hasNonzeroAlpha = true
                argb(alpha, reader.u8(offset + 2), reader.u8(offset + 1), reader.u8(offset))
            } else {
                val bitOffset = x * depth
                val paletteIndex = (reader.u8(rowOffset + bitOffset / 8) ushr (8 - depth - bitOffset % 8)) and ((1 shl depth) - 1)
                requireImage(paletteIndex < paletteSize, "ICO 索引色越界。")
                val offset = header + paletteIndex * 4
                argb(255, reader.u8(offset + 2), reader.u8(offset + 1), reader.u8(offset))
            }
            pixels[y * width + x] = color
        }
        if (depth != 32 || !hasNonzeroAlpha) for (y in 0 until height) for (x in 0 until width) {
            val transparent = hasMask && reader.u8(maskOffset + (height - 1 - y) * maskStride + x / 8) and (0x80 ushr (x % 8)) != 0
            pixels[y * width + x] = (pixels[y * width + x] and 0x00ffffff) or (if (transparent) 0 else 0xff000000.toInt())
        }
        return Raster(width, height, pixels)
    }

    fun iconEntries(bytes: ByteArray): Sequence<ByteArray> {
        val reader = Reader(bytes, true)
        requireImage(reader.u16(0) == 0 && reader.u16(2) == 1, "ICO 头无效。")
        val count = reader.u16(4)
        requireImage(count in 1..256, "ICO 图像目录无效。")
        reader.check(6, count * 16)
        return (0 until count).sortedByDescending { index ->
            val offset = 6 + index * 16
            (reader.u8(offset).takeIf { it > 0 } ?: 256) * (reader.u8(offset + 1).takeIf { it > 0 } ?: 256)
        }.asSequence().map { index ->
            val offset = 6 + index * 16
            val size = reader.u32(offset + 8).checkedInt()
            val start = reader.u32(offset + 12).checkedInt()
            reader.check(start, size)
            bytes.copyOfRange(start, start + size)
        }
    }

    internal fun packBits(bytes: ByteArray, expectedSize: Int): ByteArray {
        val output = ByteArray(expectedSize)
        var position = 0
        var target = 0
        while (target < expectedSize && position < bytes.size) {
            val count = bytes[position++].toInt()
            if (count in 0..127) {
                val length = count + 1
                requireImage(position + length <= bytes.size && target + length <= expectedSize, "TIFF PackBits 数据损坏。")
                bytes.copyInto(output, target, position, position + length)
                position += length
                target += length
            } else if (count in -127..-1) {
                val length = 1 - count
                requireImage(position < bytes.size && target + length <= expectedSize, "TIFF PackBits 数据损坏。")
                output.fill(bytes[position++], target, target + length)
                target += length
            }
        }
        requireImage(target == expectedSize, "TIFF PackBits 数据不完整。")
        return output
    }

    internal fun lzw(bytes: ByteArray, expectedSize: Int): ByteArray {
        val output = ByteArray(expectedSize)
        val prefixes = IntArray(4_096)
        val suffixes = IntArray(4_096) { it and 255 }
        val stack = IntArray(4_096)
        var dictionarySize = 258
        var codeSize = 9
        var previous = -1
        var previousFirst = 0
        var outputOffset = 0
        var sourceOffset = 0
        var bitBuffer = 0L
        var availableBits = 0
        fun readCode(): Int {
            while (availableBits < codeSize) {
                requireImage(sourceOffset < bytes.size, "TIFF LZW 数据不完整。")
                bitBuffer = (bitBuffer shl 8) or (bytes[sourceOffset++].toLong() and 255)
                availableBits += 8
            }
            availableBits -= codeSize
            return ((bitBuffer ushr availableBits) and ((1 shl codeSize) - 1).toLong()).toInt()
        }
        while (outputOffset < expectedSize) {
            val code = readCode()
            if (code == 256) { dictionarySize = 258; codeSize = 9; previous = -1; continue }
            if (code == 257) break
            requireImage(code < dictionarySize || (code == dictionarySize && previous >= 0), "TIFF LZW 码表损坏。")
            var walk = if (code == dictionarySize) previous else code
            var stackSize = 0
            while (walk >= 256) {
                requireImage(walk < dictionarySize && stackSize < stack.size - 1, "TIFF LZW 码表循环。")
                stack[stackSize++] = suffixes[walk]
                walk = prefixes[walk]
            }
            val first = walk
            stack[stackSize++] = first
            requireImage(outputOffset + stackSize + (if (code == dictionarySize) 1 else 0) <= expectedSize, "TIFF LZW 解码长度不正确。")
            for (index in stackSize - 1 downTo 0) output[outputOffset++] = stack[index].toByte()
            if (code == dictionarySize) output[outputOffset++] = previousFirst.toByte()
            if (previous >= 0 && dictionarySize < 4_096) {
                prefixes[dictionarySize] = previous
                suffixes[dictionarySize] = first
                dictionarySize++
                // TIFF uses early-change LZW (one code earlier than GIF's late-change rule).
                if (codeSize < 12 && dictionarySize == (1 shl codeSize) - 1) codeSize++
            }
            previous = code
            previousFirst = first
        }
        requireImage(outputOffset == expectedSize, "TIFF LZW 数据不完整。")
        return output
    }

    private fun inflate(bytes: ByteArray, expectedSize: Int): ByteArray {
        val output = ByteArray(expectedSize)
        var offset = 0
        InflaterInputStream(ByteArrayInputStream(bytes)).use { stream ->
            while (offset < output.size) {
                val count = stream.read(output, offset, output.size - offset)
                requireImage(count > 0, "TIFF Deflate 数据不完整。")
                offset += count
            }
            requireImage(stream.read() == -1, "TIFF Deflate 解码长度不正确。")
        }
        return output
    }

    private fun orient(raster: Raster, orientation: Int): Raster {
        if (orientation !in 2..8) return raster
        val swap = orientation in 5..8
        val outWidth = if (swap) raster.height else raster.width
        val outHeight = if (swap) raster.width else raster.height
        val output = IntArray(raster.pixels.size)
        for (y in 0 until raster.height) for (x in 0 until raster.width) {
            val (newX, newY) = when (orientation) {
                2 -> raster.width - 1 - x to y
                3 -> raster.width - 1 - x to raster.height - 1 - y
                4 -> x to raster.height - 1 - y
                5 -> y to x
                6 -> raster.height - 1 - y to x
                7 -> raster.height - 1 - y to raster.width - 1 - x
                else -> y to raster.width - 1 - x
            }
            output[newY * outWidth + newX] = raster.pixels[y * raster.width + x]
        }
        return Raster(outWidth, outHeight, output)
    }

    private class Reader(private val bytes: ByteArray, private val littleEndian: Boolean) {
        fun check(offset: Int, size: Int) = requireImage(offset >= 0 && size >= 0 && offset.toLong() + size <= bytes.size,
            "图片数据区间无效或不完整。")
        fun u8(offset: Int): Int { check(offset, 1); return bytes[offset].toInt() and 255 }
        fun u16(offset: Int): Int { check(offset, 2); return if (littleEndian) u8(offset) or (u8(offset + 1) shl 8) else (u8(offset) shl 8) or u8(offset + 1) }
        fun u32(offset: Int): Long {
            check(offset, 4)
            return (0..3).sumOf { index -> u8(offset + index).toLong() shl ((if (littleEndian) index else 3 - index) * 8) }
        }
    }
    private fun Long.checkedInt(): Int { requireImage(this in 0..Int.MAX_VALUE.toLong(), "图片整数区间超出范围。"); return toInt() }
    private fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun requireImage(condition: Boolean, message: String) { if (!condition) throw Unsupported(message) }
}
