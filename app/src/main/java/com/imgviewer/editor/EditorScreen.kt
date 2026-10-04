package com.imgviewer.editor

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Rotate90DegreesCcw
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private val EditorBlue = Color(0xFF72ABFF)
private val EditorMuted = Color(0xFF9B9BA3)
private val EditorPanel = Color(0xFF19191E)
private enum class EditorMode(val label: String) { ADJUST("调整"), HSL("色彩"), CROP("裁剪") }
private enum class BasicParameter(val label: String) { EXPOSURE("曝光"), BRIGHTNESS("亮度"), CONTRAST("对比度"), SATURATION("饱和度") }
private enum class CropPreset(val label: String) { ORIGINAL("原图"), FREE("自由"), SQUARE("1:1"), STANDARD("4:3"), WIDE("16:9") }

private val AdjustmentsSaver = listSaver<EditAdjustments, Float>(
    save = { edit ->
        buildList {
            add(edit.brightness); add(edit.exposure); add(edit.contrast); add(edit.saturation)
            add(edit.global.hue); add(edit.global.saturation); add(edit.global.lightness)
            ColorBand.entries.forEach { band ->
                val color = edit.bands[band] ?: HslAdjustment()
                add(color.hue); add(color.saturation); add(color.lightness)
            }
        }
    },
    restore = { values ->
        if (values.size != 28) null else EditAdjustments(
            brightness = values[0], exposure = values[1], contrast = values[2], saturation = values[3],
            global = HslAdjustment(values[4], values[5], values[6]),
            bands = ColorBand.entries.mapIndexed { index, band ->
                val offset = 7 + index * 3
                band to HslAdjustment(values[offset], values[offset + 1], values[offset + 2])
            }.toMap()
        )
    }
)
private val CropSaver = listSaver<CropRect, Float>(
    save = { listOf(it.left, it.top, it.right, it.bottom) },
    restore = { if (it.size == 4) CropRect(it[0], it[1], it[2], it[3]) else null }
)

private fun CropPreset.aspect(imageAspect: Float): Float? = when (this) {
    CropPreset.ORIGINAL -> imageAspect
    CropPreset.FREE -> null
    CropPreset.SQUARE -> 1f
    CropPreset.STANDARD -> 4f / 3f
    CropPreset.WIDE -> 16f / 9f
}

/** A non-destructive editor. Always saves a new PNG and leaves the source URI untouched. */
@Composable
fun EditorScreen(uri: Uri, name: String, onClose: () -> Unit, onSaved: (Uri) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember(uri) { mutableStateOf<EditorSource?>(null) }
    var retryToken by remember(uri) { mutableIntStateOf(0) }
    var loadError by remember(uri) { mutableStateOf<String?>(null) }
    var saveError by remember(uri) { mutableStateOf<String?>(null) }
    var previewError by remember(uri) { mutableStateOf<String?>(null) }
    var adjustments by rememberSaveable(uri, stateSaver = AdjustmentsSaver) { mutableStateOf(EditAdjustments()) }
    var rotation by rememberSaveable(uri) { mutableIntStateOf(0) }
    var crop by rememberSaveable(uri, stateSaver = CropSaver) { mutableStateOf(CropRect()) }
    var preset by rememberSaveable(uri) { mutableStateOf(CropPreset.FREE) }
    var mode by rememberSaveable(uri) { mutableStateOf(EditorMode.ADJUST) }
    var selectedParameter by rememberSaveable(uri) { mutableStateOf(BasicParameter.EXPOSURE) }
    var selectedBand by rememberSaveable(uri) { mutableStateOf<ColorBand?>(null) }
    var preview by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    var previewRotation by remember(uri) { mutableIntStateOf(0) }
    var updatingPreview by remember(uri) { mutableStateOf(false) }
    var saving by remember(uri) { mutableStateOf(false) }

    BackHandler { if (!saving) onClose() }

    DisposableEffect(source) {
        val currentSource = source
        onDispose { currentSource?.dispose() }
    }

    LaunchedEffect(uri, retryToken) {
        loadError = null
        try {
            source = EditorImageIO.load(context, uri)
        } catch (e: CancellationException) {
            throw e
        } catch (error: Throwable) {
            loadError = if (error is OutOfMemoryError) "这张图片过大，暂时无法加载。" else
                error.message ?: "无法读取图片，请检查文件与访问权限。"
        }
    }

    LaunchedEffect(source, adjustments, rotation) {
        val loaded = source ?: return@LaunchedEffect
        updatingPreview = true
        previewError = null
        try {
            // Rapid slider updates are coalesced; expensive pixel math never runs on the UI thread.
            delay(90)
            val rendered = loaded.withBitmap { bitmap ->
                EditorImageIO.render(bitmap, rotation, CropRect(), adjustments)
            }
            preview = rendered.asImageBitmap()
            previewRotation = rotation
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            previewError = "预览更新失败，请重置后重试。"
        } finally {
            updatingPreview = false
        }
    }

    val loaded = source
    val imageAspect = loaded?.let {
        if (rotation % 2 == 0) it.originalWidth.toFloat() / it.originalHeight
        else it.originalHeight.toFloat() / it.originalWidth
    } ?: 1f
    val exportWidth = loaded?.let { if (rotation % 2 == 0) it.exportWidth else it.exportHeight } ?: 1
    val exportHeight = loaded?.let { if (rotation % 2 == 0) it.exportHeight else it.exportWidth } ?: 1
    val outputBounds = crop.pixelBounds(exportWidth, exportHeight)

    MaterialTheme(colorScheme = darkColorScheme(primary = EditorBlue, surface = EditorPanel, background = Color.Black)) {
        Column(
            Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onClose, enabled = !saving) { Text("取消", color = EditorBlue, fontSize = 16.sp) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("编辑照片", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Text(name, color = EditorMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp)
                }
                Button(
                    onClick = {
                        val current = source ?: return@Button
                        saving = true
                        scope.launch {
                            try {
                                val saved = EditorImageIO.saveCopy(context, uri, name, current, rotation, crop, adjustments)
                                onSaved(saved)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (error: Throwable) {
                                saveError = if (error is OutOfMemoryError) "内存不足，无法保存这张图片。请关闭其他应用后重试。"
                                    else error.message ?: "保存失败，请检查可用空间。"
                            } finally {
                                saving = false
                            }
                        }
                    },
                    enabled = loaded != null && !saving,
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = EditorBlue, contentColor = Color(0xFF09182D))
                ) { Text(if (saving) "保存中" else "保存副本", fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
            }

            if (loaded?.animated == true) {
                Text(
                    "动图编辑将使用第一帧，保存为静态 PNG 副本；原动图保留。",
                    color = Color(0xFFFFD786), fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp)
                )
            }
            if (loaded?.rawPreview == true) {
                Text(
                    "RAW 使用内嵌预览编辑，按预览分辨率保存 PNG 副本。",
                    color = EditorMuted, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp)
                )
            }

            Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                val image = preview
                when {
                    loadError != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(loadError!!, color = EditorMuted, modifier = Modifier.padding(24.dp))
                        TextButton(onClick = { retryToken++ }) { Text("重新加载") }
                    }
                    image == null || previewRotation != rotation -> CircularProgressIndicator(color = EditorBlue)
                    else -> CropPreview(
                        image = image,
                        crop = crop,
                        enabled = mode == EditorMode.CROP && !saving,
                        normalizedAspect = preset.aspect(imageAspect)?.div(imageAspect),
                        onCropChange = { crop = it }
                    )
                }
                if (updatingPreview && image != null && previewRotation == rotation) {
                    CircularProgressIndicator(
                        color = EditorBlue, strokeWidth = 2.dp,
                        modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(18.dp)
                    )
                }
                if (saving) {
                    Column(
                        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.65f)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(color = EditorBlue)
                        Text("正在保存 PNG 副本…", color = Color.White, modifier = Modifier.padding(top = 16.dp))
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${outputBounds.width} × ${outputBounds.height} · PNG", color = EditorMuted, fontSize = 11.sp)
                    if (loaded != null && (loaded.exportWidth < loaded.originalWidth || loaded.exportHeight < loaded.originalHeight)) {
                        Text("超大图片按设备内存缩小保存", color = EditorMuted, fontSize = 10.sp)
                    }
                    previewError?.let { Text(it, color = Color(0xFFFFAFAF), fontSize = 11.sp) }
                }
                TextButton(
                    onClick = { adjustments = EditAdjustments(); rotation = 0; crop = CropRect(); preset = CropPreset.FREE },
                    enabled = !saving && loaded != null
                ) {
                    Icon(Icons.Rounded.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("重置", fontSize = 12.sp)
                }
            }

            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(26.dp))
                    .background(EditorPanel).padding(vertical = 10.dp)
            ) {
                when (mode) {
                    EditorMode.ADJUST -> BasicControls(
                        adjustments, selectedParameter, !saving && loaded != null,
                        onParameter = { selectedParameter = it }, onChange = { adjustments = it }
                    )
                    EditorMode.HSL -> HslControls(
                        adjustments, selectedBand, !saving && loaded != null,
                        onBandChange = { selectedBand = it }, onChange = { adjustments = it }
                    )
                    EditorMode.CROP -> CropControls(
                        preset, !saving && loaded != null,
                        onPreset = { preset = it; crop = CropRect.forAspect(it.aspect(imageAspect), imageAspect) },
                        onRotate = {
                            val newAspect = 1f / imageAspect
                            rotation = (rotation + 3) % 4
                            crop = CropRect.forAspect(preset.aspect(newAspect), newAspect)
                        }
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(50.dp)).background(Color(0xFF222229))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(50.dp)).padding(4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                EditorMode.entries.forEach { item ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(30.dp))
                            .background(if (item == mode) Color(0xFF3B3B45) else Color.Transparent)
                            .clickable(enabled = !saving) { mode = item }.padding(vertical = 9.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            when (item) { EditorMode.ADJUST -> Icons.Rounded.Tune; EditorMode.HSL -> Icons.Rounded.Palette; EditorMode.CROP -> Icons.Rounded.Crop },
                            contentDescription = item.label,
                            tint = if (item == mode) EditorBlue else EditorMuted,
                            modifier = Modifier.size(21.dp)
                        )
                        Text(item.label, color = if (item == mode) Color.White else EditorMuted, fontSize = 11.sp)
                    }
                }
            }
        }
        saveError?.let { message ->
            AlertDialog(
                onDismissRequest = { saveError = null }, title = { Text("未能保存") },
                text = { Text(message) }, confirmButton = { TextButton(onClick = { saveError = null }) { Text("知道了") } }
            )
        }
    }
}

@Composable
private fun BasicControls(
    adjustments: EditAdjustments,
    selected: BasicParameter,
    enabled: Boolean,
    onParameter: (BasicParameter) -> Unit,
    onChange: (EditAdjustments) -> Unit
) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicParameter.entries.forEach { parameter ->
            EditorChip(parameter.label, selected == parameter, enabled) { onParameter(parameter) }
        }
    }
    val value = when (selected) {
        BasicParameter.BRIGHTNESS -> adjustments.brightness
        BasicParameter.EXPOSURE -> adjustments.exposure
        BasicParameter.CONTRAST -> adjustments.contrast
        BasicParameter.SATURATION -> adjustments.saturation
    }
    EditSlider(selected.label, value, if (selected == BasicParameter.EXPOSURE) -2f..2f else -1f..1f, enabled,
        valueLabel = if (selected == BasicParameter.EXPOSURE) String.format(java.util.Locale.US, "%+.1f EV", value) else signedPercent(value)
    ) { new ->
        onChange(when (selected) {
            BasicParameter.BRIGHTNESS -> adjustments.copy(brightness = new)
            BasicParameter.EXPOSURE -> adjustments.copy(exposure = new)
            BasicParameter.CONTRAST -> adjustments.copy(contrast = new)
            BasicParameter.SATURATION -> adjustments.copy(saturation = new)
        })
    }
    Text("拖动滑杆调整，保存为新副本", color = EditorMuted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
}

@Composable
private fun HslControls(
    adjustments: EditAdjustments,
    selected: ColorBand?,
    enabled: Boolean,
    onBandChange: (ColorBand?) -> Unit,
    onChange: (EditAdjustments) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        EditorChip("全部", selected == null, enabled) { onBandChange(null) }
        ColorBand.entries.forEach { band ->
            Column(
                Modifier.clip(RoundedCornerShape(20.dp)).clickable(enabled = enabled) { onBandChange(band) }
                    .padding(horizontal = 3.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.size(25.dp).border(if (selected == band) 2.dp else 0.dp, Color.White, CircleShape)
                        .padding(if (selected == band) 4.dp else 2.dp).background(Color(band.argb), CircleShape)
                )
                Text(band.label, color = if (selected == band) Color.White else EditorMuted, fontSize = 10.sp)
            }
        }
    }
    val hsl = if (selected == null) adjustments.global else adjustments.bands[selected] ?: HslAdjustment()
    fun change(updated: HslAdjustment) {
        onChange(if (selected == null) adjustments.copy(global = updated)
            else adjustments.copy(bands = adjustments.bands + (selected to updated)))
    }
    EditSlider("色相", hsl.hue, -180f..180f, enabled, "${hsl.hue.roundToInt()}°", compact = true) { change(hsl.copy(hue = it)) }
    EditSlider("饱和度", hsl.saturation, -1f..1f, enabled, signedPercent(hsl.saturation), compact = true) { change(hsl.copy(saturation = it)) }
    EditSlider("明度", hsl.lightness, -1f..1f, enabled, signedPercent(hsl.lightness), compact = true) { change(hsl.copy(lightness = it)) }
}

@Composable
private fun CropControls(preset: CropPreset, enabled: Boolean, onPreset: (CropPreset) -> Unit, onRotate: () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CropPreset.entries.forEach { item -> EditorChip(item.label, preset == item, enabled) { onPreset(item) } }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("拖动四角裁剪\n拖动框内移动选区", color = EditorMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onRotate, enabled = enabled) {
            Icon(Icons.Rounded.Rotate90DegreesCcw, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
            Text("旋转 90°")
        }
    }
}

@Composable
private fun EditorChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, enabled = enabled, label = { Text(label, fontSize = 12.sp) },
        shape = RoundedCornerShape(22.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color(0xFF26262E), selectedContainerColor = Color(0xFF344969),
            labelColor = EditorMuted, selectedLabelColor = Color(0xFFC4DBFF)
        ), border = null
    )
}

private fun signedPercent(value: Float): String = String.format(java.util.Locale.US, "%+d", (value * 100f).roundToInt())

@Composable
private fun EditSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    valueLabel: String,
    compact: Boolean = false,
    onChange: (Float) -> Unit
) {
    if (compact) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(45.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = EditorMuted, fontSize = 12.sp, modifier = Modifier.width(48.dp))
            Slider(
                value = value.coerceIn(range), onValueChange = onChange, valueRange = range, enabled = enabled,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = EditorBlue, inactiveTrackColor = Color(0xFF3B3B43))
            )
            Text(valueLabel, color = Color.White, fontSize = 11.sp, modifier = Modifier.width(42.dp))
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, color = EditorMuted, fontSize = 13.sp)
                Text(valueLabel, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Slider(
                value = value.coerceIn(range), onValueChange = onChange, valueRange = range, enabled = enabled,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = EditorBlue, inactiveTrackColor = Color(0xFF3B3B43))
            )
        }
    }
}

private fun fitRect(canvas: Size, width: Float, height: Float): Rect {
    val scale = min(canvas.width / width, canvas.height / height)
    val w = width * scale
    val h = height * scale
    val x = (canvas.width - w) / 2f
    val y = (canvas.height - h) / 2f
    return Rect(x, y, x + w, y + h)
}

@Composable
private fun CropPreview(
    image: ImageBitmap,
    crop: CropRect,
    enabled: Boolean,
    normalizedAspect: Float?,
    onCropChange: (CropRect) -> Unit
) {
    val currentCrop by rememberUpdatedState(crop)
    val currentOnCropChange by rememberUpdatedState(onCropChange)
    Canvas(
        Modifier.fillMaxSize().semantics {
            contentDescription = if (enabled) "裁剪照片：拖动四角缩放选区，拖动框内移动选区" else "编辑后的照片预览"
            stateDescription = "选区 ${(crop.width * 100).roundToInt()}% × ${(crop.height * 100).roundToInt()}%"
        }.pointerInput(image.width, image.height, enabled, normalizedAspect) {
            if (!enabled) return@pointerInput
            var handle: CropHandle? = null
            detectDragGestures(
                onDragStart = { point ->
                    val display = fitRect(Size(size.width.toFloat(), size.height.toFloat()), image.width.toFloat(), image.height.toFloat())
                    val c = currentCrop
                    val selection = Rect(
                        display.left + display.width * c.left, display.top + display.height * c.top,
                        display.left + display.width * c.right, display.top + display.height * c.bottom
                    )
                    val hitRadius = 32.dp.toPx()
                    val corners = listOf(
                        selection.topLeft to CropHandle.TOP_LEFT, selection.topRight to CropHandle.TOP_RIGHT,
                        selection.bottomLeft to CropHandle.BOTTOM_LEFT, selection.bottomRight to CropHandle.BOTTOM_RIGHT
                    )
                    handle = corners.minByOrNull { (corner, _) -> (point - corner).getDistance() }
                        ?.takeIf { (corner, _) -> (point - corner).getDistance() <= hitRadius }?.second
                        ?: if (selection.contains(point)) CropHandle.MOVE else null
                },
                onDragEnd = { handle = null }, onDragCancel = { handle = null },
                onDrag = { change, delta ->
                    val activeHandle = handle ?: return@detectDragGestures
                    change.consume()
                    val display = fitRect(Size(size.width.toFloat(), size.height.toFloat()), image.width.toFloat(), image.height.toFloat())
                    currentOnCropChange(currentCrop.resized(activeHandle, delta.x / display.width, delta.y / display.height, normalizedAspect))
                }
            )
        }
    ) {
        val bounds = if (enabled) PixelCrop(0, 0, image.width, image.height) else crop.pixelBounds(image.width, image.height)
        val display = fitRect(size, bounds.width.toFloat(), bounds.height.toFloat())
        drawImage(
            image, srcOffset = IntOffset(bounds.left, bounds.top), srcSize = IntSize(bounds.width, bounds.height),
            dstOffset = IntOffset(display.left.roundToInt(), display.top.roundToInt()),
            dstSize = IntSize(display.width.roundToInt().coerceAtLeast(1), display.height.roundToInt().coerceAtLeast(1))
        )
        if (enabled) {
            val selection = Rect(
                display.left + display.width * crop.left, display.top + display.height * crop.top,
                display.left + display.width * crop.right, display.top + display.height * crop.bottom
            )
            val dim = Color.Black.copy(alpha = 0.6f)
            drawRect(dim, display.topLeft, Size(display.width, selection.top - display.top))
            drawRect(dim, Offset(display.left, selection.bottom), Size(display.width, display.bottom - selection.bottom))
            drawRect(dim, Offset(display.left, selection.top), Size(selection.left - display.left, selection.height))
            drawRect(dim, Offset(selection.right, selection.top), Size(display.right - selection.right, selection.height))
            drawRect(Color.White, selection.topLeft, selection.size, style = Stroke(1.4.dp.toPx()))
            for (index in 1..2) {
                val x = selection.left + selection.width * index / 3f
                val y = selection.top + selection.height * index / 3f
                drawLine(Color.White.copy(alpha = 0.45f), Offset(x, selection.top), Offset(x, selection.bottom), 0.5.dp.toPx())
                drawLine(Color.White.copy(alpha = 0.45f), Offset(selection.left, y), Offset(selection.right, y), 0.5.dp.toPx())
            }
            val cornerLength = min(20.dp.toPx(), min(selection.width, selection.height) / 4f)
            val stroke = 3.dp.toPx()
            listOf(selection.topLeft, selection.topRight, selection.bottomLeft, selection.bottomRight).forEach { corner ->
                val xSign = if (abs(corner.x - selection.left) < 1f) 1f else -1f
                val ySign = if (abs(corner.y - selection.top) < 1f) 1f else -1f
                drawLine(Color.White, corner, corner + Offset(cornerLength * xSign, 0f), stroke)
                drawLine(Color.White, corner, corner + Offset(0f, cornerLength * ySign), stroke)
            }
        }
    }
}
