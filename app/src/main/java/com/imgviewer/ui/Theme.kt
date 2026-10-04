package com.imgviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp

val AppleBlue = Color(0xFF007AFF)
val Ink = Color(0xFF161619)
val SecondaryInk = Color(0xFF86868B)
val Paper = Color(0xFFFAFAFC)

@Composable
fun ImgViewerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(
        primary = AppleBlue, background = Paper, surface = Paper,
        onSurface = Ink, onBackground = Ink, secondary = SecondaryInk
    ), content = content)
}

@Composable
fun GlassSurface(modifier: Modifier = Modifier, dark: Boolean = false, backdrop: GraphicsLayer? = null, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(100)
    val frosted = rememberGraphicsLayer()
    var position by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.shadow(18.dp, shape, ambientColor = Color.Black.copy(.15f), spotColor = Color.Black.copy(.12f))
        .clip(shape)
        .onGloballyPositioned { position = it.positionInRoot() }
        .drawWithContent {
            if (backdrop != null && android.os.Build.VERSION.SDK_INT >= 31 && backdrop.size.width > 0) {
                frosted.renderEffect = BlurEffect(24f, 24f)
                frosted.record(size = backdrop.size) { drawLayer(backdrop) }
                translate(-position.x, -position.y) { drawLayer(frosted) }
            }
            drawContent()
        }
        .background(Brush.verticalGradient(if (dark) listOf(Color(0xEE303036), Color(0xDD202027))
        else listOf(Color.White.copy(if (backdrop != null) .70f else .93f), Color(0xFFECECF2).copy(if (backdrop != null) .60f else .9f))))
        .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(if (dark) .22f else .98f), Color.White.copy(.2f))), shape), content = content)
}
