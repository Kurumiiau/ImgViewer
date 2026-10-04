package com.imgviewer.ui

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.core.graphics.drawable.toBitmap
import com.imgviewer.data.MediaItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.min

internal data class ThumbnailAnchor(val owner: Any, val bounds: () -> Rect?, val drawable: Drawable?)
internal data class TransitionPicture(val image: ImageBitmap, val bounds: Rect)

/** Only visible cells are retained. Coordinates and drawables are never persisted across rotation. */
class PhotoTransitionRegistry {
    private val thumbnails = mutableMapOf<Uri, ThumbnailAnchor>()
    internal fun update(uri: Uri, owner: Any, bounds: Rect, drawable: Drawable?) {
        update(uri, owner, { bounds }, drawable)
    }
    internal fun update(uri: Uri, owner: Any, bounds: () -> Rect?, drawable: Drawable?) {
        thumbnails[uri] = ThumbnailAnchor(owner, bounds, drawable)
    }
    internal fun remove(uri: Uri, owner: Any) {
        if (thumbnails[uri]?.owner === owner) thumbnails.remove(uri)
    }
    internal fun bounds(uri: Uri): Rect? = thumbnails[uri]?.bounds?.invoke()?.takeIf { it.width > 1f && it.height > 1f }
    internal fun picture(uri: Uri): TransitionPicture? {
        val anchor = thumbnails[uri] ?: return null
        val drawable = anchor.drawable ?: return null
        val bounds = anchor.bounds.invoke()?.takeIf { it.width > 1f && it.height > 1f } ?: return null
        return runCatching {
            val width = drawable.intrinsicWidth.coerceAtLeast(1)
            val height = drawable.intrinsicHeight.coerceAtLeast(1)
            val ratio = min(1f, 1600f / max(width, height))
            val bitmap = drawable.toBitmap((width * ratio).toInt().coerceAtLeast(1),
                (height * ratio).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val software = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
            TransitionPicture(software.asImageBitmap(), bounds)
        }.getOrNull()
    }
}

internal class ViewerVisual(
    val viewport: Rect,
    val imageBounds: Rect,
    val ready: Boolean,
    val capture: suspend () -> TransitionPicture?
)

internal fun fittedPhotoBounds(viewport: Rect, aspect: Float): Rect {
    val ratio = aspect.takeIf { it.isFinite() && it > 0f } ?: 1f
    val width = min(viewport.width, viewport.height * ratio)
    val height = min(viewport.height, viewport.width / ratio)
    return Rect(viewport.center.x - width / 2, viewport.center.y - height / 2,
        viewport.center.x + width / 2, viewport.center.y + height / 2)
}

private fun mixBounds(start: Rect, end: Rect, progress: Float): Rect {
    fun mix(a: Float, b: Float) = a + (b - a) * progress
    return Rect(mix(start.left, end.left), mix(start.top, end.top), mix(start.right, end.right), mix(start.bottom, end.bottom))
}

/** The same progress reverses from its current value when Back interrupts an opening animation. */
@Composable
fun PhotoViewerTransition(
    items: List<MediaItem>, initialUri: String, visible: Boolean, registry: PhotoTransitionRegistry,
    onClosed: () -> Unit, onCloseRequest: () -> Unit,
    onFavorite: (MediaItem) -> Unit, onDelete: (MediaItem) -> Unit, onEdit: (MediaItem) -> Unit,
    onCurrentUriChange: (String) -> Unit
) {
    val progress = remember { Animatable(0f) }
    val heroAlpha = remember { Animatable(1f) }
    var hero by remember { mutableStateOf(registry.picture(Uri.parse(initialUri))) }
    var visual by remember { mutableStateOf<ViewerVisual?>(null) }
    var currentUri by remember { mutableStateOf(initialUri) }
    var exitTarget by remember { mutableStateOf<Rect?>(null) }
    var exitImageBounds by remember { mutableStateOf<Rect?>(null) }
    var closing by remember { mutableStateOf(false) }
    var interrupted by remember { mutableStateOf(false) }
    val closedCallback by rememberUpdatedState(onClosed)
    val shown by rememberUpdatedState(visible)
    val easing = remember { CubicBezierEasing(.2f, .8f, .2f, 1f) }
    val motionReady = visual?.viewport?.let { it.width > 1 && it.height > 1 } == true

    LaunchedEffect(visible, motionReady, interrupted) {
        if (visible && motionReady) {
            closing = false
            if (interrupted) {
                progress.snapTo(1f)
                heroAlpha.snapTo(0f)
                hero = null
                return@LaunchedEffect
            }
            progress.animateTo(1f, tween(360, easing = easing))
            // Keep a decoded thumbnail visible until the full image has pixels to show.
            withTimeoutOrNull(1200) { snapshotFlow { visual?.ready == true }.first { it } }
            heroAlpha.animateTo(0f, tween(100))
            hero = null
        } else if (!visible) {
            closing = true
            exitTarget = registry.bounds(Uri.parse(currentUri))
            exitImageBounds = hero?.let { source -> visual?.let { fittedPhotoBounds(it.viewport, source.image.width.toFloat() / source.image.height) } }
            if (hero == null && exitTarget != null) {
                hero = visual?.capture?.invoke()
                if (hero != null) { exitImageBounds = hero?.bounds; heroAlpha.snapTo(1f) }
            }
            // A missing cell (scrolled away, deleted, or filtered out) uses a gentle fade.
            if (exitTarget == null) hero = null
            progress.animateTo(0f, tween(280, easing = easing))
            if (!shown) closedCallback()
        }
    }
    val amount = progress.value
    val picture = hero
    // Blend back into the real cell: a zoomed view or a moving WebP frame can
    // have a different crop/content from the static gallery thumbnail.
    val pictureAlpha = heroAlpha.value * if (closing) (amount / .2f).coerceIn(0f, 1f) else 1f
    val controlsAlpha = ((amount - .35f) / .65f).coerceIn(0f, 1f)
    val imageAlpha = if (picture != null) 1f - heroAlpha.value else amount
    val destination = if (closing) exitTarget else visual?.let {
        if (it.ready) it.imageBounds else picture?.let { source -> fittedPhotoBounds(it.viewport, source.image.width.toFloat() / source.image.height) } ?: it.imageBounds
    }
    val start = if (closing) exitTarget else picture?.bounds
    val end = if (closing) exitImageBounds else destination
    val rect = if (start != null && end != null) mixBounds(start, end, amount) else null
    val painter = remember(picture?.image) { picture?.let { BitmapPainter(it.image) } }
    Box(Modifier.fillMaxSize().testTag("photo_transition")
        .semantics { stateDescription = if (closing) "closing:$amount" else "opening:$amount" }) {
        ViewerScreen(items, initialUri, onCloseRequest,
            onFavorite = { if (visible) onFavorite(it) },
            onDelete = { if (visible) onDelete(it) },
            onEdit = { if (visible) onEdit(it) },
            onCurrentUriChange = { uri ->
                if (uri != currentUri) { hero = null; currentUri = uri; if (visible) interrupted = true }
                onCurrentUriChange(uri)
            },
            backgroundAlpha = amount, chromeAlpha = controlsAlpha, imageAlpha = imageAlpha,
            imageScale = if (picture == null) .955f + .045f * amount else 1f,
            interactionEnabled = visible,
            onInteraction = { if (visible && (amount < 1f || hero != null)) interrupted = true },
            onVisualChange = { visual = it })
        if (painter != null && rect != null && rect.width > 0 && rect.height > 0) {
            Canvas(Modifier.fillMaxSize().testTag("photo_transition_image")) {
                val intrinsic = painter.intrinsicSize
                val scale = max(rect.width / intrinsic.width, rect.height / intrinsic.height)
                val drawWidth = intrinsic.width * scale
                val drawHeight = intrinsic.height * scale
                clipRect(rect.left, rect.top, rect.right, rect.bottom) {
                    translate(rect.center.x - drawWidth / 2, rect.center.y - drawHeight / 2) {
                        with(painter) { draw(androidx.compose.ui.geometry.Size(drawWidth, drawHeight), pictureAlpha) }
                    }
                }
            }
        }
    }
}
