package com.imgviewer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.drawWithContent
import android.graphics.Bitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.imgviewer.data.MediaItem
import com.imgviewer.image.MediaImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.min

@Composable
internal fun ViewerScreen(
    items: List<MediaItem>, initialUri: String,
    onClose: () -> Unit, onFavorite: (MediaItem) -> Unit,
    onDelete: (MediaItem) -> Unit, onEdit: (MediaItem) -> Unit,
    onCurrentUriChange: (String) -> Unit,
    backgroundAlpha: Float = 1f,
    chromeAlpha: Float = 1f,
    imageAlpha: Float = 1f,
    imageScale: Float = 1f,
    interactionEnabled: Boolean = true,
    onInteraction: () -> Unit = {},
    onVisualChange: (ViewerVisual?) -> Unit = {}
) {
    val pager = rememberPagerState(initialPage = items.indexOfFirst { it.uri.toString() == initialUri }.coerceAtLeast(0)) { items.size }
    val scope = rememberCoroutineScope()
    val interactionCallback by rememberUpdatedState(onInteraction)
    val current = items[pager.currentPage.coerceIn(items.indices)]
    var controls by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
    var playing by rememberSaveable(current.uri.toString()) { mutableStateOf(true) }
    var actualAnimation by remember(current.uri) { mutableStateOf(false) }
    val date = remember(current.dateTaken) { Instant.ofEpochMilli(current.dateTaken).atZone(ZoneId.systemDefault()) }
    val rawPreview = current.name.substringAfterLast('.', "").lowercase() in setOf("dng", "cr2", "crw", "nef", "nrw", "arw", "rw2", "orf", "pef", "srw", "raf") ||
        current.mimeType in setOf("image/x-adobe-dng", "image/x-canon-cr2", "image/x-canon-crw", "image/x-nikon-nef", "image/x-nikon-nrw", "image/x-sony-arw", "image/x-panasonic-rw2", "image/x-olympus-orf", "image/x-pentax-pef", "image/x-samsung-srw", "image/x-fuji-raf", "image/x-fujifilm-raf")
    LaunchedEffect(current.uri) { zoomed = false; onCurrentUriChange(current.uri.toString()) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = backgroundAlpha))
        .pointerInput(Unit) {
            // A full-screen pointer target keeps the gallery out of hit testing.
            // Leave events unconsumed so the pager and photo gestures can finish.
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { it.pressed && !it.previousPressed }) interactionCallback()
                }
            }
        }) {
        // Insets reserve room for controls even when hidden, so tapping never resizes the photo.
        HorizontalPager(state = pager, key = { items[it].uri.toString() }, userScrollEnabled = !zoomed && interactionEnabled,
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(top = 88.dp, bottom = 148.dp)) { page ->
            val item = items[page]
            ZoomablePhoto(item, isCurrent = page == pager.currentPage, playing = if (page == pager.currentPage) playing && imageAlpha > .2f else false,
                imageAlpha = imageAlpha, imageScale = imageScale, interactionEnabled = interactionEnabled, onVisualChange = onVisualChange,
                onTap = { controls = !controls }, onZoom = { if (page == pager.currentPage) zoomed = it },
                onAnimation = { if (page == pager.currentPage) actualAnimation = it })
        }
        AnimatedVisibility(visible = controls, modifier = Modifier.align(Alignment.TopCenter).graphicsLayer { alpha = chromeAlpha; translationY = -12.dp.toPx() * (1f - chromeAlpha) }) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircleButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回照片", onClose, dark = true)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(date.format(DateTimeFormatter.ofPattern("yyyy年M月d日")), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(current.name, color = Color.White.copy(.6f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${pager.currentPage + 1} / ${items.size}", color = Color.White.copy(.45f), fontSize = 10.sp)
                        if (actualAnimation) Text("  ·  ${if (playing) "动图播放中" else "已暂停"}", color = Color(0xFF85BFFF), fontSize = 10.sp)
                        if (rawPreview) Text("  ·  RAW 内嵌预览", color = Color(0xFF85BFFF), fontSize = 10.sp)
                    }
                }
                CircleButton(Icons.Rounded.Info, "照片信息", { if (interactionEnabled) info = true }, dark = true)
            }
        }
        AnimatedVisibility(visible = controls, modifier = Modifier.align(Alignment.BottomCenter).graphicsLayer { alpha = chromeAlpha; translationY = 18.dp.toPx() * (1f - chromeAlpha) }) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (actualAnimation) {
                    TextButton(onClick = { playing = !playing }, enabled = interactionEnabled, modifier = Modifier.height(32.dp)) {
                        Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(16.dp), tint = Color.White)
                        Text(if (playing) "暂停" else "播放", Modifier.padding(start = 4.dp), fontSize = 12.sp, color = Color.White)
                    }
                }
                if (items.size > 1) {
                    LazyRow(Modifier.height(48.dp).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(items, key = { _, item -> item.uri.toString() }) { index, item ->
                            Box(Modifier.width(if (index == pager.currentPage) 40.dp else 30.dp).height(42.dp)
                                .clip(RoundedCornerShape(5.dp)).background(if (index == pager.currentPage) AppleBlue else Color(0xFF202023))
                                .clickable(enabled = interactionEnabled) { if (index != pager.currentPage) scope.launch { pager.animateScrollToPage(index) } }.padding(if (index == pager.currentPage) 2.dp else 0.dp)) {
                                MediaImage(item, Modifier.fillMaxSize().clip(RoundedCornerShape(3.dp)))
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                GlassSurface(Modifier.width(302.dp).height(67.dp), dark = true) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        ViewerAction(if (current.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            if (current.isFavorite) "已收藏" else "收藏", if (current.isFavorite) Color(0xFFFF375F) else Color.White) { onFavorite(current) }
                        ViewerAction(Icons.Rounded.DeleteOutline, "删除") { onDelete(current) }
                        ViewerAction(Icons.Rounded.Tune, "编辑") { onEdit(current) }
                    }
                }
            }
        }
        if (info) AlertDialog(onDismissRequest = { info = false },
            title = { Text("照片信息") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(current.name, fontWeight = FontWeight.SemiBold)
                InfoRow("拍摄时间", date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                InfoRow("图集", current.albumName)
                InfoRow("格式", current.mimeType.removePrefix("image/").uppercase())
                InfoRow("尺寸", if (current.width > 0 && current.height > 0) "${current.width} × ${current.height}" else "未记录")
                InfoRow("大小", if (current.size > 0) android.text.format.Formatter.formatFileSize(LocalContext.current, current.size) else "未记录")
                InfoRow("类型", if (actualAnimation) "动态图片" else "静态图片")
                if (rawPreview) Text("显示的是 RAW 文件内嵌的预览图，编辑保存采用预览分辨率。", color = SecondaryInk, fontSize = 12.sp)
                Text("双指缩放或双击放大，左右滑动查看其他照片。", color = SecondaryInk, fontSize = 12.sp)
            } }, confirmButton = { TextButton(onClick = { info = false }) { Text("完成") } })
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row { Text(label, Modifier.width(76.dp), fontSize = 13.sp, color = SecondaryInk); Text(value, fontSize = 13.sp) }
}

@Composable
private fun RowScope.ViewerAction(icon: ImageVector, label: String, color: Color = Color.White, onClick: () -> Unit) {
    Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(25.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, label, Modifier.size(23.dp), tint = color)
        Text(label, color = color, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ZoomablePhoto(item: MediaItem, isCurrent: Boolean, playing: Boolean, imageAlpha: Float, imageScale: Float,
    interactionEnabled: Boolean, onVisualChange: (ViewerVisual?) -> Unit,
    onTap: () -> Unit, onZoom: (Boolean) -> Unit, onAnimation: (Boolean) -> Unit) {
    var scale by remember(item.uri) { mutableFloatStateOf(1f) }
    var offset by remember(item.uri) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var error by remember(item.uri) { mutableStateOf<String?>(null) }
    var animationActual by remember(item.uri) { mutableStateOf(false) }
    var drawableAspect by remember(item.uri) { mutableFloatStateOf(0f) }
    var loaded by remember(item.uri) { mutableStateOf(false) }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    var viewportFullyVisible by remember { mutableStateOf(false) }
    val captureLayer = rememberGraphicsLayer()
    val activeInteraction by rememberUpdatedState(interactionEnabled)
    val visualCallback by rememberUpdatedState(onVisualChange)
    val currentOnZoom by rememberUpdatedState(onZoom)
    val currentOnAnimation by rememberUpdatedState(onAnimation)
    fun clamp(value: Offset, atScale: Float): Offset {
        val aspect = drawableAspect.takeIf { it > 0f } ?: if (item.width > 0 && item.height > 0) item.width.toFloat() / item.height else if (size.height > 0) size.width.toFloat() / size.height else 1f
        val fittedWidth = min(size.width.toFloat(), size.height * aspect)
        val fittedHeight = min(size.height.toFloat(), size.width / aspect)
        val x = ((fittedWidth * atScale - size.width) / 2).coerceAtLeast(0f)
        val y = ((fittedHeight * atScale - size.height) / 2).coerceAtLeast(0f)
        return Offset(value.x.coerceIn(-x, x), value.y.coerceIn(-y, y))
    }
    LaunchedEffect(isCurrent) { if (!isCurrent) { scale = 1f; offset = Offset.Zero } }
    LaunchedEffect(isCurrent, scale) { if (isCurrent) currentOnZoom(scale > 1.01f) }
    LaunchedEffect(isCurrent, animationActual) { if (isCurrent) currentOnAnimation(animationActual) }
    LaunchedEffect(isCurrent, viewport, viewportFullyVisible, drawableAspect, scale, offset, loaded) {
        if (isCurrent && viewport.width > 0 && viewport.height > 0) {
            val aspect = drawableAspect.takeIf { it > 0 } ?: if (item.width > 0 && item.height > 0) item.width.toFloat() / item.height else 1f
            val fitted = fittedPhotoBounds(Rect(0f, 0f, viewport.width, viewport.height), aspect)
            val image = Rect(fitted.center.x - fitted.width * scale / 2 + offset.x,
                fitted.center.y - fitted.height * scale / 2 + offset.y,
                fitted.center.x + fitted.width * scale / 2 + offset.x,
                fitted.center.y + fitted.height * scale / 2 + offset.y)
            val clipped = image.intersect(Rect(0f, 0f, viewport.width, viewport.height))
            val absolute = clipped.translate(viewport.topLeft)
            visualCallback(ViewerVisual(viewport, absolute, loaded) {
                if (!viewportFullyVisible) return@ViewerVisual null
                try {
                    val frame = captureLayer.toImageBitmap().asAndroidBitmap()
                    val left = clipped.left.toInt().coerceIn(0, frame.width - 1)
                    val top = clipped.top.toInt().coerceIn(0, frame.height - 1)
                    val width = kotlin.math.ceil(clipped.width).toInt().coerceIn(1, frame.width - left)
                    val height = kotlin.math.ceil(clipped.height).toInt().coerceIn(1, frame.height - top)
                    TransitionPicture(Bitmap.createBitmap(frame, left, top, width, height).asImageBitmap(), absolute)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            })
        }
    }
    Box(Modifier.fillMaxSize().testTag("viewer_image").onSizeChanged { size = it }
        .onGloballyPositioned {
            val position = it.positionInRoot()
            viewport = Rect(position.x, position.y, position.x + it.size.width, position.y + it.size.height)
            val clipped = it.boundsInRoot()
            viewportFullyVisible = kotlin.math.abs(clipped.width - it.size.width) < 1f && kotlin.math.abs(clipped.height - it.size.height) < 1f
        }
        .pointerInput(item.uri) {
            detectTapGestures(onTap = { if (activeInteraction) onTap() }, onDoubleTap = { point ->
                if (!activeInteraction) return@detectTapGestures
                if (scale > 1.01f) { scale = 1f; offset = Offset.Zero }
                else { scale = 3f; offset = clamp((Offset(size.width / 2f, size.height / 2f) - point) * 2f, scale) }
            })
        }
        .pointerInput(item.uri) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (activeInteraction && (event.changes.count { it.pressed } >= 2 || scale > 1.01f && event.changes.any { it.pressed && it.previousPressed && it.position != it.previousPosition })) {
                        val zoom = event.calculateZoom()
                        val nextScale = (scale * zoom).coerceIn(1f, 8f)
                        val ratio = nextScale / scale
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val centroid = event.calculateCentroid(useCurrent = false)
                        offset = clamp(offset * ratio + (center - centroid) * (ratio - 1f) + event.calculatePan(), nextScale)
                        scale = nextScale
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }.clip(RoundedCornerShape(0.dp)).graphicsLayer { alpha = imageAlpha; scaleX = imageScale; scaleY = imageScale }
        .drawWithContent { captureLayer.record { this@drawWithContent.drawContent() }; drawLayer(captureLayer) }, contentAlignment = Alignment.Center) {
        MediaImage(item, Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
            animate = playing, prepareAnimation = isCurrent, contentScale = ContentScale.Fit,
            onError = { error = it; animationActual = false; loaded = true }, onSuccess = { error = null; animationActual = it; loaded = true },
            onDrawable = { drawable -> if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) drawableAspect = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight })
        error?.let { message ->
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.BrokenImage, null, Modifier.size(42.dp), tint = Color.White.copy(.5f))
                Text("暂时无法显示这张图片", color = Color.White, modifier = Modifier.padding(top = 14.dp))
                Text(message, color = Color.White.copy(.6f), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
