package com.imgviewer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * Place at CenterEnd inside a Box that has the same height as its grid. The 48 dp strip is the
 * only input area; the rest of the grid keeps normal scrolling and pinch gestures. Labels should
 * come from a prebuilt index or a binary search of date sections, never a scan of the library.
 */
@Composable
internal fun GridFastScroller(
    state: LazyGridState,
    modifier: Modifier = Modifier,
    labelForIndex: (Int) -> String = { "" }
) {
    val scrollable by remember(state) { derivedStateOf { state.canScrollBackward || state.canScrollForward } }
    if (!scrollable) return

    val density = LocalDensity.current
    val maxBubbleWidth = (LocalConfiguration.current.screenWidthDp - 80).coerceAtLeast(80).dp
    val thumbHeight = with(density) { 48.dp.toPx() }
    var trackHeight by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var showLabel by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    var requestedIndex by remember { mutableIntStateOf(0) }
    val currentLabel by rememberUpdatedState(labelForIndex)
    val requests = remember(state) { Channel<Int>(Channel.CONFLATED) }
    DisposableEffect(requests) { onDispose { requests.close() } }
    LaunchedEffect(requests) {
        // One worker, one pending destination. A long drag cannot queue thousands of old jumps.
        for (index in requests) {
            val count = state.layoutInfo.totalItemsCount
            if (count > 0) try {
                state.scrollToItem(index.coerceIn(0, count - 1))
            } catch (cancelled: CancellationException) {
                // A concurrent grid gesture can supersede a jump without killing this worker.
                coroutineContext.ensureActive()
            }
        }
    }
    LaunchedEffect(dragging) {
        if (dragging) showLabel = true
        else { delay(650); showLabel = false }
    }
    val position by remember(state) { derivedStateOf {
        val layout = state.layoutInfo
        val count = layout.totalItemsCount
        when {
            count <= 1 || !state.canScrollBackward -> 0f
            !state.canScrollForward -> 1f
            else -> {
                val first = layout.visibleItemsInfo.firstOrNull { it.index == state.firstVisibleItemIndex }
                val rowCount = if (first != null) layout.visibleItemsInfo.count { it.offset.y == first.offset.y } else 1
                val withinRow = state.firstVisibleItemScrollOffset.toFloat() / (first?.size?.height ?: 1).coerceAtLeast(1)
                val lastStart = (count - layout.visibleItemsInfo.size).coerceAtLeast(1)
                ((state.firstVisibleItemIndex + withinRow * rowCount) / lastStart).coerceIn(0f, 1f)
            }
        }
    } }
    val progress = if (dragging) dragProgress else position
    val label = currentLabel(if (dragging) requestedIndex else state.firstVisibleItemIndex)
    val active by animateFloatAsState(if (dragging) 1f else 0f, tween(150), label = "fastScrollerActive")
    val travel = (trackHeight - thumbHeight).coerceAtLeast(0f)

    fun jump(fraction: Float) {
        val layout = state.layoutInfo
        val count = layout.totalItemsCount
        if (count == 0) return
        dragProgress = fraction.coerceIn(0f, 1f)
        // The exact last item guarantees bottom access even when headers and footer have spans.
        val lastStart = (count - layout.visibleItemsInfo.size).coerceAtLeast(0)
        requestedIndex = if (dragProgress >= .999f) count - 1
            else (dragProgress * lastStart).roundToInt().coerceIn(0, count - 1)
        requests.trySend(requestedIndex)
    }

    Box(modifier.fillMaxHeight().padding(top = 12.dp, bottom = 110.dp).width(48.dp)) {
        Box(Modifier.fillMaxHeight().width(48.dp)
            .testTag("grid_fast_scroller")
            .onSizeChanged { trackHeight = it.height }
            .semantics {
                contentDescription = "快速滚动"
                stateDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                setProgress { value -> if (value.isFinite()) { jump(value); true } else false }
                customActions = listOf(
                    CustomAccessibilityAction("滚动到顶部") { jump(0f); true },
                    CustomAccessibilityAction("滚动到底部") { jump(1f); true }
                )
            }
            .pointerInput(state, thumbHeight) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val available = (size.height - thumbHeight).coerceAtLeast(0f)
                    if (available == 0f) return@awaitEachGesture
                    val thumbTop = position * available
                    val grabOffset = if (down.position.y in thumbTop..(thumbTop + thumbHeight))
                        down.position.y - thumbTop else thumbHeight / 2f
                    dragging = true
                    jump((down.position.y - grabOffset) / available)
                    down.consume()
                    try {
                        do {
                            val event = awaitPointerEvent()
                            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                            jump((pointer.position.y - grabOffset) / available)
                            pointer.consume()
                        } while (pointer.pressed)
                    } finally {
                        dragging = false
                    }
                }
            }) {
            Canvas(Modifier.fillMaxHeight().width(48.dp)) {
                val width = 4.dp.toPx() + active * 5.dp.toPx()
                val x = size.width - 11.dp.toPx() - width
                val y = progress * (size.height - thumbHeight).coerceAtLeast(0f)
                drawRoundRect(Color.White.copy(alpha = .82f), Offset(x - 1.dp.toPx(), y - 1.dp.toPx()),
                    Size(width + 2.dp.toPx(), thumbHeight + 2.dp.toPx()), CornerRadius(width))
                drawRoundRect(if (dragging) AppleBlue else SecondaryInk.copy(alpha = .52f),
                    Offset(x, y), Size(width, thumbHeight), CornerRadius(width))
            }
        }
        AnimatedVisibility(visible = showLabel && label.isNotBlank(), modifier = Modifier.align(Alignment.TopEnd)
            .offset { IntOffset(-49.dp.roundToPx(), (progress * travel + thumbHeight / 2f - 20.dp.toPx()).roundToInt()) }
            .wrapContentWidth(Alignment.End, unbounded = true).widthIn(max = maxBubbleWidth)) {
            Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.background(Ink.copy(alpha = .88f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}
