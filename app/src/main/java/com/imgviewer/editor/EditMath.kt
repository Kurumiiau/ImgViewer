package com.imgviewer.editor

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt

/** Color values are kept independent of Android so the same math drives previews and exports. */
data class Hsl(val hue: Float, val saturation: Float, val lightness: Float)

enum class ColorBand(val label: String, val center: Float, val argb: Long) {
    RED("红", 0f, 0xFFFF5B63), ORANGE("橙", 30f, 0xFFFFA347),
    YELLOW("黄", 60f, 0xFFFFDB52), GREEN("绿", 120f, 0xFF69D882),
    CYAN("青", 180f, 0xFF63DCE6), BLUE("蓝", 240f, 0xFF6397FF),
    PURPLE("紫", 300f, 0xFFC28BFA)
}

data class HslAdjustment(val hue: Float = 0f, val saturation: Float = 0f, val lightness: Float = 0f)

data class EditAdjustments(
    val brightness: Float = 0f,
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val global: HslAdjustment = HslAdjustment(),
    val bands: Map<ColorBand, HslAdjustment> = emptyMap()
) {
    val isIdentity: Boolean get() = brightness == 0f && exposure == 0f && contrast == 0f &&
        saturation == 0f && global == HslAdjustment() && bands.values.all { it == HslAdjustment() }
}

object EditColorMath {
    fun rgbToHsl(argb: Int): Hsl {
        val r = ((argb ushr 16) and 255) / 255f
        val g = ((argb ushr 8) and 255) / 255f
        val b = (argb and 255) / 255f
        val high = max(r, max(g, b))
        val low = min(r, min(g, b))
        val delta = high - low
        val lightness = (high + low) / 2f
        if (delta < 0.000001f) return Hsl(0f, 0f, lightness)
        val saturation = delta / (1f - abs(2f * lightness - 1f))
        val hue = when (high) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        return Hsl(wrapHue(hue), saturation, lightness)
    }

    fun hslToArgb(hsl: Hsl, alpha: Int = 255): Int = fromHsl(
        hsl.hue, hsl.saturation, hsl.lightness, alpha
    )

    fun wrapHue(hue: Float): Float = ((hue % 360f) + 360f) % 360f

    private fun fromHsl(hue: Float, saturation: Float, lightness: Float, alpha: Int): Int {
        val h = wrapHue(hue) / 60f
        val s = saturation.coerceIn(0f, 1f)
        val l = lightness.coerceIn(0f, 1f)
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs(h % 2f - 1f))
        val m = l - c / 2f
        val r: Float
        val g: Float
        val b: Float
        when (h.toInt()) {
            0 -> { r = c; g = x; b = 0f }
            1 -> { r = x; g = c; b = 0f }
            2 -> { r = 0f; g = c; b = x }
            3 -> { r = 0f; g = x; b = c }
            4 -> { r = x; g = 0f; b = c }
            else -> { r = c; g = 0f; b = x }
        }
        return (alpha.coerceIn(0, 255) shl 24) or
            (((r + m) * 255f).roundToInt().coerceIn(0, 255) shl 16) or
            (((g + m) * 255f).roundToInt().coerceIn(0, 255) shl 8) or
            ((b + m) * 255f).roundToInt().coerceIn(0, 255)
    }

    /** Smooth circular selection avoids seams around red at 0 / 360 degrees. */
    fun bandWeight(hue: Float, center: Float): Float {
        val difference = abs(wrapHue(hue) - wrapHue(center))
        val distance = min(difference, 360f - difference)
        val t = (1f - distance / 45f).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Prepared once per image, with no per-pixel HSL object allocation. Alpha is never adjusted. */
    class Processor(private val adjustments: EditAdjustments) {
        private val identity = adjustments.isIdentity
        private val exposureScale = 2.0.pow(adjustments.exposure.toDouble()).toFloat()
        private val contrastScale = 1f + adjustments.contrast
        private val offset = adjustments.brightness * 0.25f
        private val bands = adjustments.bands.filterValues { it != HslAdjustment() }.entries.toList()

        fun transform(argb: Int): Int {
            if (identity || argb ushr 24 == 0) return argb
            var r = ((argb ushr 16) and 255) / 255f
            var g = ((argb ushr 8) and 255) / 255f
            var b = (argb and 255) / 255f
            r = ((r * exposureScale - 0.5f) * contrastScale + 0.5f + offset).coerceIn(0f, 1f)
            g = ((g * exposureScale - 0.5f) * contrastScale + 0.5f + offset).coerceIn(0f, 1f)
            b = ((b * exposureScale - 0.5f) * contrastScale + 0.5f + offset).coerceIn(0f, 1f)
            val high = max(r, max(g, b))
            val low = min(r, min(g, b))
            val delta = high - low
            var lightness = (high + low) / 2f
            var saturation = if (delta < 0.000001f) 0f else delta / (1f - abs(2f * lightness - 1f))
            var hue = if (delta < 0.000001f) 0f else wrapHue(when (high) {
                r -> 60f * (((g - b) / delta) % 6f)
                g -> 60f * ((b - r) / delta + 2f)
                else -> 60f * ((r - g) / delta + 4f)
            })
            // Select using the original hue; shifting red must not retarget it to another band.
            var hueShift = adjustments.global.hue
            var saturationShift = adjustments.global.saturation
            var lightnessShift = adjustments.global.lightness
            if (saturation > 0.00001f) {
                for (index in bands.indices) {
                    val (band, edit) = bands[index]
                    val weight = bandWeight(hue, band.center)
                    hueShift += edit.hue * weight
                    saturationShift += edit.saturation * weight
                    lightnessShift += edit.lightness * weight
                }
            }
            hue = wrapHue(hue + hueShift)
            saturation = (saturation * (1f + adjustments.saturation) * (1f + saturationShift)).coerceIn(0f, 1f)
            lightnessShift = lightnessShift.coerceIn(-1f, 1f)
            lightness = if (lightnessShift > 0f) lightness + (1f - lightness) * lightnessShift
                else lightness * (1f + lightnessShift)
            return fromHsl(hue, saturation, lightness, argb ushr 24)
        }
    }
}

data class PixelCrop(val left: Int, val top: Int, val width: Int, val height: Int)
enum class CropHandle { MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/** Coordinates are normalized to the rotated image, never to the view or screen. */
data class CropRect(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun moved(dx: Float, dy: Float): CropRect {
        val x = dx.coerceIn(-left, 1f - right)
        val y = dy.coerceIn(-top, 1f - bottom)
        return copy(left = left + x, right = right + x, top = top + y, bottom = bottom + y)
    }

    fun resized(handle: CropHandle, dx: Float, dy: Float, normalizedAspect: Float?): CropRect {
        if (handle == CropHandle.MOVE) return moved(dx, dy)
        val moveLeft = handle == CropHandle.TOP_LEFT || handle == CropHandle.BOTTOM_LEFT
        val moveTop = handle == CropHandle.TOP_LEFT || handle == CropHandle.TOP_RIGHT
        val anchorX = if (moveLeft) right else left
        val anchorY = if (moveTop) bottom else top
        val maxWidth = if (moveLeft) anchorX else 1f - anchorX
        val maxHeight = if (moveTop) anchorY else 1f - anchorY
        var newWidth = (width + if (moveLeft) -dx else dx).coerceIn(min(0.05f, maxWidth), maxWidth)
        var newHeight = (height + if (moveTop) -dy else dy).coerceIn(min(0.05f, maxHeight), maxHeight)
        if (normalizedAspect != null && normalizedAspect > 0f) {
            val ratio = normalizedAspect
            if (abs(dx) >= abs(dy) * ratio) newHeight = newWidth / ratio else newWidth = newHeight * ratio
            val maxAllowedWidth = min(maxWidth, maxHeight * ratio)
            val minimumWidth = min(maxAllowedWidth, max(0.05f, 0.05f * ratio))
            newWidth = newWidth.coerceIn(minimumWidth, maxAllowedWidth)
            newHeight = newWidth / ratio
        }
        return CropRect(
            if (moveLeft) anchorX - newWidth else anchorX,
            if (moveTop) anchorY - newHeight else anchorY,
            if (moveLeft) anchorX else anchorX + newWidth,
            if (moveTop) anchorY else anchorY + newHeight
        )
    }

    fun pixelBounds(imageWidth: Int, imageHeight: Int): PixelCrop {
        require(imageWidth > 0 && imageHeight > 0)
        // Normalized Float coordinates such as 0.099999994 represent a mathematically exact
        // 80-pixel edge on an 800-pixel image. Snap only sub-thousandth-pixel rounding noise;
        // genuinely fractional edges still use outward floor / ceil coverage.
        fun pixelEdge(normalized: Float, dimension: Int): Double {
            val coordinate = normalized.coerceIn(0f, 1f).toDouble() * dimension
            val nearest = round(coordinate)
            return if (abs(coordinate - nearest) <= 0.001) nearest else coordinate
        }
        val x = floor(pixelEdge(left, imageWidth)).toInt().coerceIn(0, imageWidth - 1)
        val y = floor(pixelEdge(top, imageHeight)).toInt().coerceIn(0, imageHeight - 1)
        val endX = ceil(pixelEdge(right, imageWidth)).toInt().coerceIn(x + 1, imageWidth)
        val endY = ceil(pixelEdge(bottom, imageHeight)).toInt().coerceIn(y + 1, imageHeight)
        return PixelCrop(x, y, endX - x, endY - y)
    }

    companion object {
        fun forAspect(aspect: Float?, imageAspect: Float): CropRect {
            if (aspect == null) return CropRect()
            require(aspect > 0f && imageAspect > 0f)
            val normalized = aspect / imageAspect
            val width = min(1f, normalized)
            val height = min(1f, 1f / normalized)
            return CropRect((1f - width) / 2f, (1f - height) / 2f, (1f + width) / 2f, (1f + height) / 2f)
        }
    }
}
