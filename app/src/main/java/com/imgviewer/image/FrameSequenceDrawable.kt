package com.imgviewer.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.io.IOException
import kotlin.math.roundToInt

/**
 * Frame position and the remaining duration belong to this clock. stop/start pause and resume
 * that clock; neither operation resets the decoder. Draw always paints the last published frame,
 * so zoom, transition snapshots and unrelated Compose redraws cannot advance a paused animation.
 */
internal class FrameSequenceDrawable(
    private val compositor: AnimationCompositor,
    firstFrame: AnimationCompositor.DecodedFrame,
    orientation: Int = 1,
) : Drawable(), Animatable {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val workerJob = SupervisorJob()
    private val worker = CoroutineScope(workerJob + Dispatchers.IO)
    private var current = firstFrame
    @Volatile private var next: AnimationCompositor.DecodedFrame? = null
    @Volatile private var decodeError = false
    private var prefetching = false
    private var running = false
    private var scheduled = false
    private var closed = false
    private var remainingMillis = firstFrame.durationMillis.toLong()
    private var nextDeadline = 0L
    private val swapsAxes = orientation in 5..8
    private val drawMatrix = Matrix()
    private val orientationMatrix = Matrix().apply {
        val w = compositor.width.toFloat(); val h = compositor.height.toFloat()
        val values = when (orientation) {
            2 -> floatArrayOf(-1f, 0f, w, 0f, 1f, 0f, 0f, 0f, 1f)
            3 -> floatArrayOf(-1f, 0f, w, 0f, -1f, h, 0f, 0f, 1f)
            4 -> floatArrayOf(1f, 0f, 0f, 0f, -1f, h, 0f, 0f, 1f)
            5 -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
            6 -> floatArrayOf(0f, -1f, h, 1f, 0f, 0f, 0f, 0f, 1f)
            7 -> floatArrayOf(0f, -1f, h, -1f, 0f, w, 0f, 0f, 1f)
            8 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, w, 0f, 0f, 1f)
            else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        }
        setValues(values)
    }
    internal var onDecodeFailure: ((Throwable) -> Unit)? = null
    internal val frameIndex: Int get() = current.index
    internal val repeatCount: Int get() = -1

    init { workerJob.invokeOnCompletion { compositor.close() } }

    private val advance = object : Runnable {
        override fun run() {
            scheduled = false
            if (!running || closed) return
            val now = SystemClock.uptimeMillis()
            if (now < nextDeadline) { schedule(); return }
            val decoded = next
            if (decoded == null) {
                if (decodeError) stop()
                else schedule(now + 16)
                return
            }
            current = decoded
            next = null
            prefetching = false
            remainingMillis = decoded.durationMillis.toLong()
            nextDeadline = now + remainingMillis
            invalidateSelf()
            prefetch()
            schedule()
        }
    }

    override fun draw(canvas: Canvas) {
        drawMatrix.set(orientationMatrix)
        drawMatrix.postScale(bounds.width().toFloat() / intrinsicWidth, bounds.height().toFloat() / intrinsicHeight)
        drawMatrix.postTranslate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.drawBitmap(current.bitmap, drawMatrix, paint)
        if (running) { prefetch(); if (!scheduled) schedule() }
    }

    override fun start() {
        if (running || closed || decodeError) return
        running = true
        nextDeadline = SystemClock.uptimeMillis() + remainingMillis
        prefetch()
        schedule()
        invalidateSelf()
    }

    override fun stop() {
        if (!running) return
        remainingMillis = (nextDeadline - SystemClock.uptimeMillis()).coerceAtLeast(0)
        running = false
        unscheduleSelf(advance)
        scheduled = false
    }

    override fun isRunning() = running
    override fun getIntrinsicWidth() = if (swapsAxes) compositor.height else compositor.width
    override fun getIntrinsicHeight() = if (swapsAxes) compositor.width else compositor.height
    @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun setAlpha(alpha: Int) { paint.alpha = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun getAlpha() = paint.alpha
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    override fun getColorFilter(): ColorFilter? = paint.colorFilter

    /** Disposal releases the compositor after the worker has finished using it. */
    internal fun close() {
        if (closed) return
        stop()
        closed = true
        onDecodeFailure = null
        worker.cancel()
        next = null
    }

    private fun prefetch() {
        if (prefetching || closed || decodeError) return
        prefetching = true
        worker.launch {
            try { next = compositor.nextFrame() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) {
                decodeError = true
                Handler(Looper.getMainLooper()).post { if (!closed) { stop(); onDecodeFailure?.invoke(error) } }
            }
            // The next frame may remain ready throughout a pause. It is only published by advance.
        }
    }

    private fun schedule(deadline: Long = nextDeadline) {
        if (!running || closed || callback == null) return
        scheduleSelf(advance, deadline)
        scheduled = true
    }
}

/** Sequential compositor retains a canvas and, only for GIF disposal 3, a restoration canvas. */
internal class AnimationCompositor(private val animation: AnimationFrames, maxEdge: Int) {
    internal data class DecodedFrame(val index: Int, val durationMillis: Int, val bitmap: Bitmap)

    private var sample = 1
    init {
        while (maxOf(animation.width, animation.height).toLong() / sample > maxEdge) sample *= 2
    }
    val width = (animation.width.toFloat() / sample).roundToInt().coerceAtLeast(1)
    val height = (animation.height.toFloat() / sample).roundToInt().coerceAtLeast(1)
    private val canvasBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(canvasBitmap)
    private val blendPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val replacePaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    private var restoreBitmap: Bitmap? = null
    private var index = -1

    fun nextFrame(): DecodedFrame {
        if (index >= 0) dispose(animation.frames[index])
        index = (index + 1) % animation.frames.size
        if (index == 0) canvas.drawColor(animation.background, PorterDuff.Mode.SRC)
        val frame = animation.frames[index]
        if (frame.disposal == 3) restoreBitmap = canvasBitmap.copy(Bitmap.Config.ARGB_8888, false)
        val encoded = animation.encodedFrame(frame)
        val bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }) ?: throw IOException("无法解码动画的第 ${index + 1} 帧。")
        try {
            canvas.drawBitmap(bitmap, null, rectangle(frame), if (frame.blend) blendPaint else replacePaint)
        } finally { bitmap.recycle() }
        return DecodedFrame(index, frame.durationMillis,
            canvasBitmap.copy(Bitmap.Config.ARGB_8888, false) ?: throw IOException("动画帧分配失败。"))
    }

    fun close() {
        restoreBitmap?.recycle(); restoreBitmap = null
        canvasBitmap.recycle()
    }

    private fun rectangle(frame: AnimationFrames.Frame): RectF = RectF(
        frame.x.toFloat() * width / animation.width,
        frame.y.toFloat() * height / animation.height,
        (frame.x.toFloat() + frame.width) * width / animation.width,
        (frame.y.toFloat() + frame.height) * height / animation.height,
    )

    private fun dispose(frame: AnimationFrames.Frame) {
        when (frame.disposal) {
            2 -> {
                replacePaint.color = if (frame.gif && frame.transparentIndex >= 0) 0 else animation.background
                canvas.drawRect(rectangle(frame), replacePaint)
                replacePaint.color = android.graphics.Color.BLACK
            }
            3 -> restoreBitmap?.let { previous ->
                canvas.drawBitmap(previous, 0f, 0f, replacePaint)
                previous.recycle(); restoreBitmap = null
            }
        }
    }
}
