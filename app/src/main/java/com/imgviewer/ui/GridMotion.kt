package com.imgviewer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize

internal val GridCellVisualSize = SemanticsPropertyKey<IntSize>("GridCellVisualSize")
internal val GridCellTargetSize = SemanticsPropertyKey<IntSize>("GridCellTargetSize")

/**
 * Keep this modifier on the outer node of an item with a stable key. Lazy-grid placement moves
 * that node, while this layer also interpolates its old physical size into the new cell size.
 * Only composed cells hold an animation; changing density never builds all library thumbnails.
 */
@Composable
internal fun LazyGridItemScope.gridMotion(modifier: Modifier = Modifier): Modifier {
    var targetSize by remember { mutableStateOf(IntSize.Zero) }
    val visualSize = remember { Animatable(IntSize.Zero, IntSize.VectorConverter) }
    LaunchedEffect(targetSize) {
        if (targetSize.width <= 0 || targetSize.height <= 0) return@LaunchedEffect
        if (visualSize.value == IntSize.Zero) visualSize.snapTo(targetSize)
        else visualSize.animateTo(targetSize, tween(280, easing = FastOutSlowInEasing))
    }
    return modifier
        .animateItem(
            fadeInSpec = null,
            placementSpec = tween(280, easing = FastOutSlowInEasing),
            fadeOutSpec = null
        )
        .onSizeChanged { targetSize = it }
        .graphicsLayer {
            val drawnSize = visualSize.value
            scaleX = if (drawnSize.width > 0 && targetSize.width > 0)
                drawnSize.width.toFloat() / targetSize.width else 1f
            scaleY = if (drawnSize.height > 0 && targetSize.height > 0)
                drawnSize.height.toFloat() / targetSize.height else 1f
            transformOrigin = TransformOrigin(0f, 0f)
            // Resizing needs to draw beyond the newly measured (smaller) cell for a few frames.
            clip = false
        }
        .semantics {
            this[GridCellVisualSize] = visualSize.value.takeUnless { it == IntSize.Zero } ?: targetSize
            this[GridCellTargetSize] = targetSize
        }
}
