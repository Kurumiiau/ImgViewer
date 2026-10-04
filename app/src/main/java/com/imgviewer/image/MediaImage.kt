package com.imgviewer.image

import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import coil.drawable.ScaleDrawable
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.repeatCount
import com.imgviewer.data.MediaItem

/** Static grid thumbnails, real animation in the viewer, and frame-preserving play/pause. */
@Composable
fun MediaImage(
    item: MediaItem,
    modifier: Modifier = Modifier,
    animate: Boolean = false,
    contentScale: ContentScale = ContentScale.Crop,
    onError: (String) -> Unit = {},
    onSuccess: (Boolean) -> Unit = {},
    onDrawable: (Drawable) -> Unit = {},
    prepareAnimation: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var animationEnabled by remember(item.uri) { mutableStateOf(animate || prepareAnimation) }
    var drawable by remember(item.uri) { mutableStateOf<Animatable?>(null) }
    val playing by rememberUpdatedState(animate)
    val successCallback by rememberUpdatedState(onSuccess)
    val errorCallback by rememberUpdatedState(onError)
    val drawableCallback by rememberUpdatedState(onDrawable)

    // Once the full drawable exists, pausing retains its current frame and decoder.
    LaunchedEffect(animate, prepareAnimation, item.uri) { if (animate || prepareAnimation) animationEnabled = true }
    val loader = remember(context, animationEnabled) {
        if (animationEnabled) ImageLoaderFactory.create(context) else ImageLoaderFactory.thumbnails(context)
    }
    val request = remember(context, item.uri, item.size, item.dateTaken, animationEnabled) {
        ImageRequest.Builder(context)
            .data(item.uri)
            .memoryCacheKey("${item.uri}:${item.size}:${item.dateTaken}:${if (animationEnabled) "full" else "thumb"}")
            .repeatCount(AnimatedImageDrawable.REPEAT_INFINITE)
            .apply {
                // The opening transition reuses the decoded grid bitmap in a
                // software snapshot. Hardware bitmaps cannot be drawn there.
                if (!animationEnabled) allowHardware(false)
                if (item.name.substringAfterLast('.', "").lowercase() in RAW_EXTENSIONS) {
                    decoderFactory(ExtraImageDecoder.Factory(forceRaw = true))
                }
                // Animated drawables have callbacks and playback state; do not share one across cells.
                if (animationEnabled) memoryCachePolicy(CachePolicy.DISABLED)
            }
            .build()
    }

    fun updatePlayback() {
        if (playing && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) drawable?.start()
        else drawable?.stop()
    }
    LaunchedEffect(animate, drawable) { updatePlayback() }
    DisposableEffect(lifecycle, drawable) {
        val ownedDrawable = drawable
        val observer = LifecycleEventObserver { _, _ -> updatePlayback() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            ownedDrawable?.stop()
        }
    }
    DisposableEffect(drawable) {
        val ownedDrawable = drawable
        onDispose {
            val child = if (ownedDrawable is ScaleDrawable) ownedDrawable.child else ownedDrawable
            (child as? FrameSequenceDrawable)?.close()
        }
    }

    AsyncImage(
        model = request,
        imageLoader = loader,
        contentDescription = item.name,
        modifier = modifier,
        contentScale = contentScale,
        onSuccess = { state ->
            drawable = state.result.drawable as? Animatable
            val child = if (state.result.drawable is ScaleDrawable) (state.result.drawable as ScaleDrawable).child else state.result.drawable
            (child as? FrameSequenceDrawable)?.onDecodeFailure = { error ->
                errorCallback(if (error is OutOfMemoryError) "动画尺寸过大，内存不足以继续播放。"
                    else "动画的后续帧损坏，无法继续播放。")
            }
            updatePlayback()
            successCallback(drawable != null)
            drawableCallback(state.result.drawable)
        },
        onError = { state ->
            drawable = null
            val message = when (state.result.throwable) {
                is SecurityException -> "无法读取图片，请重新授权或重新导入。"
                is java.io.FileNotFoundException -> "图片已移动或删除，请刷新相册。"
                is RasterCodecs.Unsupported -> state.result.throwable.message ?: "暂不支持此图片编码。"
                is java.io.IOException -> state.result.throwable.message?.takeIf {
                    it.startsWith("动画") || it.startsWith("WebP") || it.startsWith("GIF") || it.startsWith("无法解码动画")
                } ?: "无法解码这张图片：文件可能损坏，或当前设备尚不支持该格式。"
                else -> "无法解码这张图片：文件可能损坏，或当前设备尚不支持该格式。"
            }
            errorCallback(message)
        },
    )
}

private val RAW_EXTENSIONS = setOf("dng", "cr2", "crw", "nef", "nrw", "arw", "rw2", "orf", "pef", "srw", "raf")
