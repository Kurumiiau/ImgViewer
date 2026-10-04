package com.imgviewer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import android.graphics.drawable.Drawable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.imgviewer.data.MediaItem
import com.imgviewer.image.MediaImage
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val FAVORITES = "@favorites"
private const val ANIMATIONS = "@animations"
private const val IMPORTS = "@imports"

internal fun Modifier.pinchGrid(onPinch: (Boolean) -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var accumulated = 1f
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                accumulated *= event.calculateZoom()
                if (accumulated > 1.20f) { onPinch(true); accumulated = 1f }
                if (accumulated < .82f) { onPinch(false); accumulated = 1f }
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

private data class GridBookmark(val index: Int, val offset: Int)
private data class PhotoSection(val date: LocalDate, val photos: List<MediaItem>, val start: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    state: GalleryState,
    fullAccess: Boolean,
    partialAccess: Boolean,
    onPermission: () -> Unit,
    onImport: () -> Unit,
    onOpen: (MediaItem, List<MediaItem>) -> Unit,
    onRefresh: () -> Unit,
    transitionRegistry: PhotoTransitionRegistry? = null,
    onDeleteSelected: (List<MediaItem>) -> Unit = {},
    onShareSelected: (List<MediaItem>) -> Unit = {},
    actionsBusy: Boolean = false,
    onAbout: () -> Unit = {}
) {
    val preferences = LocalContext.current.getSharedPreferences("gallery_ui", 0)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var albumId by rememberSaveable { mutableStateOf<String?>(null) }
    var albumTitle by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var search by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var columns by rememberSaveable { mutableIntStateOf(preferences.getInt("columns", 3).coerceIn(1, 8)) }
    var albumColumns by rememberSaveable { mutableIntStateOf(preferences.getInt("album_columns", 2).coerceIn(1, 4)) }
    val currentColumns by rememberUpdatedState(columns)
    val currentAlbumColumns by rememberUpdatedState(albumColumns)
    val photoState = rememberLazyGridState()
    val albumsState = rememberLazyGridState()
    val albumBookmarks = rememberSaveable(saver = listSaver<MutableMap<String, GridBookmark>, String>(
        save = { map -> map.flatMap { (key, value) -> listOf(key, value.index.toString(), value.offset.toString()) } },
        restore = { values -> values.chunked(3).associateTo(mutableMapOf()) { it[0] to GridBookmark(it[1].toInt(), it[2].toInt()) } }
    )) { mutableMapOf<String, GridBookmark>() }
    val detailState = rememberSaveable(albumId, saver = LazyGridState.Saver) {
        val saved = albumBookmarks[albumId]
        LazyGridState(saved?.index ?: 0, saved?.offset ?: 0)
    }
    val gridState = if (albumId == null) photoState else detailState
    var selecting by remember { mutableStateOf(false) }
    val selectedUris = remember { mutableStateMapOf<String, Boolean>() }
    val haptics = LocalHapticFeedback.current
    val backdrop = rememberGraphicsLayer()
    val images = remember(state.images, albumId, filter, query) {
        state.images.filter { item ->
            val included = when (albumId) {
                FAVORITES -> item.isFavorite
                ANIMATIONS -> item.isAnimatedCandidate
                IMPORTS -> item.albumId == "imported" || item.albumId == "documents"
                null -> true
                else -> item.albumId == albumId
            }
            included && (filter != 1 || item.isAnimatedCandidate) && (filter != 2 || item.isFavorite) && matchesSearch(item, query)
        }
    }
    val sections = remember(images) {
        var index = 0
        images.groupBy { dateOf(it.dateTaken) }.map { (date, photos) ->
            PhotoSection(date, photos, index).also { index += 1 + photos.size }
        }
    }
    fun gridDate(index: Int): String {
        val found = sections.binarySearch { it.start.compareTo(index) }
        val position = if (found >= 0) found else -found - 2
        return sections.getOrNull(position)?.let { dateLabel(it.date) }.orEmpty()
    }
    fun rememberAlbumPosition() {
        albumId?.let { albumBookmarks[it] = GridBookmark(detailState.firstVisibleItemIndex, detailState.firstVisibleItemScrollOffset) }
    }
    fun leaveAlbum() { rememberAlbumPosition(); albumId = null; filter = 0 }
    fun cancelSelection() { selectedUris.clear(); selecting = false }
    LaunchedEffect(state.images) {
        if (selectedUris.isNotEmpty()) {
            val available = state.images.mapTo(hashSetOf()) { it.uri.toString() }
            selectedUris.keys.toList().filterNot { it in available }.forEach { selectedUris.remove(it) }
            if (selectedUris.isEmpty()) selecting = false
        }
    }
    BackHandler(enabled = selecting || search || albumId != null) {
        if (selecting) { if (!actionsBusy) cancelSelection() }
        else if (search) { search = false; query = "" } else leaveAlbum()
    }
    val showAlbums = tab == 1 && albumId == null && !search
    Box(Modifier.fillMaxSize().background(Paper)) {
        Column(Modifier.fillMaxSize().drawWithContent {
            backdrop.record { this@drawWithContent.drawContent() }
            drawLayer(backdrop)
        }) {
            Column(Modifier.statusBarsPadding().padding(horizontal = 22.dp).padding(top = 12.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (albumId != null && !selecting) {
                        IconButton(onClick = ::leaveAlbum, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回图集", tint = AppleBlue)
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(if (selecting) "选择照片" else if (search) "搜索" else if (albumId != null) albumTitle else if (tab == 0) "照片" else "图集",
                            fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (selecting) "已选择 ${selectedUris.size} 张" else if (showAlbums) "每一段回忆，都有归处" else "${images.size} 张照片${if (images.isNotEmpty()) " · 按时间排序" else ""}",
                            color = SecondaryInk, fontSize = 13.sp)
                    }
                    if (selecting) {
                        TextButton(onClick = ::cancelSelection, enabled = !actionsBusy) { Text("取消") }
                    } else {
                    CircleButton(Icons.Rounded.Info, "关于 ImgViewer", onAbout)
                    Spacer(Modifier.width(9.dp))
                    CircleButton(Icons.Rounded.Add, "选择文件", onImport)
                    Spacer(Modifier.width(9.dp))
                    CircleButton(if (search) Icons.Rounded.Close else Icons.Rounded.Search,
                        if (search) "关闭搜索" else "搜索", { search = !search; if (!search) query = "" })
                    }
                }
                if (selecting) {
                    val allSelected = images.isNotEmpty() && selectedUris.size >= images.size && images.all { selectedUris.containsKey(it.uri.toString()) }
                    TextButton(onClick = {
                        Snapshot.withMutableSnapshot {
                            if (allSelected) selectedUris.clear() else images.forEach { selectedUris[it.uri.toString()] = true }
                        }
                    }, enabled = !actionsBusy, modifier = Modifier.padding(top = 8.dp)) { Text(if (allSelected) "取消全选" else "全选") }
                } else if (search) {
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("名称、图集、格式或日期") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "清除搜索") } },
                        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color.Transparent, focusedBorderColor = AppleBlue.copy(.35f),
                            unfocusedContainerColor = Color(0xFFF0F0F5), focusedContainerColor = Color(0xFFF0F0F5)))
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("WEBP", "GIF", "PNG", "收藏").forEach { term ->
                            SmallChip(term, query.equals(term, ignoreCase = true)) { query = if (query.equals(term, true)) "" else term }
                        }
                    }
                } else if (!showAlbums && state.images.isNotEmpty()) {
                    Row(Modifier.padding(top = 15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf("全部", "动图", "收藏").forEachIndexed { i, text -> SmallChip(text, filter == i) { filter = i } }
                        Spacer(Modifier.weight(1f))
                        Text("${columns} 列", color = SecondaryInk, fontSize = 12.sp)
                    }
                }
            }
            if (!fullAccess) {
                Row(Modifier.fillMaxWidth().background(AppleBlue.copy(.05f)).padding(horizontal = 22.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(16.dp), tint = AppleBlue)
                    Text(if (partialAccess) "仅显示已授权的照片" else "开启权限，浏览本机相册", Modifier.weight(1f).padding(start = 8.dp), fontSize = 12.sp, color = SecondaryInk)
                    TextButton(onClick = onPermission) { Text(if (partialAccess) "管理" else "允许访问", fontSize = 12.sp) }
                }
            }
            when {
                state.loading && state.images.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AppleBlue, strokeWidth = 2.dp) }
                state.images.isEmpty() -> EmptyLibrary(fullAccess || partialAccess, onPermission, onImport, onRefresh)
                showAlbums -> AlbumGrid(state.images, albumColumns, albumsState, Modifier.weight(1f).pinchGrid { enlarge ->
                    albumColumns = (currentAlbumColumns + if (enlarge) -1 else 1).coerceIn(1, 4)
                    preferences.edit().putInt("album_columns", albumColumns).apply()
                }) { id, title -> albumId = id; albumTitle = title; filter = 0 }
                images.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.SearchOff, null, Modifier.size(48.dp), tint = SecondaryInk.copy(.5f))
                        Text(if (search) "没有找到相关照片" else "这里还没有照片", Modifier.padding(top = 14.dp), color = SecondaryInk)
                        TextButton(onClick = { query = ""; filter = 0 }) { Text("显示全部") }
                    }
                }
                else -> Box(Modifier.weight(1f)) {
                    LazyVerticalGrid(columns = GridCells.Fixed(columns), state = gridState,
                    modifier = Modifier.fillMaxSize().testTag("photo_grid").pinchGrid { enlarge ->
                        columns = (currentColumns + if (enlarge) -1 else 1).coerceIn(1, 8)
                        preferences.edit().putInt("columns", columns).apply()
                    }, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(bottom = 128.dp)) {
                    sections.forEach { (date, photos, _) ->
                        item(key = "date:$date", span = { GridItemSpan(maxLineSpan) }) {
                            Row(Modifier.animateItem().fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(dateLabel(date), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                                Spacer(Modifier.weight(1f))
                                Text("${photos.size} 张", fontSize = 12.sp, color = SecondaryInk)
                            }
                        }
                        items(photos, key = { it.uri.toString() }) { item ->
                            PhotoCell(item, columns <= 4, transitionRegistry, modifier = gridMotion(),
                                selecting = selecting, selected = selectedUris.containsKey(item.uri.toString()), enabled = !actionsBusy,
                                onLongClick = {
                                    if (!selecting) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    selecting = true; selectedUris[item.uri.toString()] = true
                                }) {
                                if (selecting) {
                                    val key = item.uri.toString()
                                    if (selectedUris.containsKey(key)) selectedUris.remove(key) else selectedUris[key] = true
                                } else onOpen(item, images)
                            }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.fillMaxWidth().padding(top = 25.dp, bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${images.size} 张照片", fontSize = 13.sp, color = SecondaryInk)
                            Text("双指缩放，调整网格大小", fontSize = 11.sp, color = SecondaryInk.copy(.7f), modifier = Modifier.padding(top = 5.dp))
                            TextButton(onClick = onRefresh) { Text("刷新", fontSize = 12.sp) }
                        }
                    }
                    }
                    GridFastScroller(gridState, Modifier.align(Alignment.CenterEnd), ::gridDate)
                }
            }
        }
        if (selecting) {
            GlassSurface(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp).width(306.dp).height(70.dp).testTag("selection_toolbar"), backdrop = backdrop) {
                Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                    SelectionAction(Icons.Rounded.Share, "分享", "分享所选照片", selectedUris.isNotEmpty() && !actionsBusy) {
                        onShareSelected(state.images.filter { selectedUris.containsKey(it.uri.toString()) })
                    }
                    Text(if (actionsBusy) "处理中…" else "${selectedUris.size} 张", color = SecondaryInk, fontSize = 13.sp)
                    SelectionAction(Icons.Rounded.DeleteOutline, "删除", "删除所选照片", selectedUris.isNotEmpty() && !actionsBusy) {
                        onDeleteSelected(state.images.filter { selectedUris.containsKey(it.uri.toString()) })
                    }
                }
            }
        } else GlassSurface(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp).width(268.dp).height(70.dp), backdrop = backdrop) {
            Row(Modifier.fillMaxSize().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(Icons.Rounded.PhotoLibrary to "照片", Icons.Rounded.CollectionsBookmark to "图集").forEachIndexed { i, (icon, label) ->
                    Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(100))
                        .background(if (tab == i) Color.White.copy(.83f) else Color.Transparent)
                        .clickable { rememberAlbumPosition(); tab = i; albumId = null; search = false; query = ""; filter = 0 },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(icon, label, Modifier.size(24.dp), tint = if (tab == i) AppleBlue else Ink)
                        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (tab == i) AppleBlue else Ink, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(hasAccess: Boolean, onPermission: () -> Unit, onImport: () -> Unit, onRefresh: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(bottom = 110.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Box(Modifier.size(104.dp).clip(RoundedCornerShape(28.dp)).background(AppleBlue.copy(.08f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(50.dp), tint = AppleBlue)
            }
            Text("让回忆动起来", fontSize = 25.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp))
            Text("流畅查看动态 WebP 与 GIF\n用熟悉的方式，浏览你的每一张照片", color = SecondaryInk,
                fontSize = 14.sp, lineHeight = 24.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
            Button(onClick = if (hasAccess) onRefresh else onPermission, shape = RoundedCornerShape(100), modifier = Modifier.padding(top = 24.dp).height(48.dp).width(180.dp)) {
                Text(if (hasAccess) "刷新照片" else "访问照片", fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = onImport) { Text("选择文件") }
            Text("ImgViewer · 你的本地相册", fontSize = 11.sp, color = SecondaryInk.copy(.7f), modifier = Modifier.padding(top = 20.dp))
        }
    }
}

@Composable
internal fun CircleButton(icon: ImageVector, label: String, onClick: () -> Unit, dark: Boolean = false) {
    IconButton(onClick, Modifier.size(43.dp).clip(CircleShape).background(if (dark) Color.White.copy(.12f) else Color(0xFFF0F0F4))
        .border(1.dp, if (dark) Color.White.copy(.08f) else Color.White, CircleShape)) {
        Icon(icon, label, Modifier.size(22.dp), tint = if (dark) Color.White else Ink)
    }
}

@Composable
private fun SmallChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(label, color = if (selected) Color.White else SecondaryInk, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(100)).background(if (selected) Ink else Color(0xFFF0F0F4)).clickable(onClick = onClick).padding(horizontal = 15.dp, vertical = 8.dp))
}

@Composable
private fun SelectionAction(icon: ImageVector, label: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(82.dp).fillMaxHeight().clip(RoundedCornerShape(28.dp)).clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        val color = if (enabled) AppleBlue else SecondaryInk.copy(.35f)
        Icon(icon, description, Modifier.size(24.dp), tint = color)
        Text(label, color = color, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoCell(item: MediaItem, badges: Boolean, registry: PhotoTransitionRegistry?,
    modifier: Modifier = Modifier, selecting: Boolean = false, selected: Boolean = false,
    enabled: Boolean = true, onLongClick: () -> Unit = {}, onClick: () -> Unit) {
    var failed by remember(item.uri) { mutableStateOf(false) }
    val owner = remember(item.uri) { Any() }
    val coordinates = remember(item.uri) { arrayOfNulls<LayoutCoordinates>(1) }
    val currentBounds: () -> Rect? = remember(item.uri) {
        {
            coordinates[0]?.takeIf { it.isAttached }?.let {
                val clipped = it.boundsInRoot()
                val complete = Rect(it.localToRoot(Offset.Zero), it.localToRoot(Offset(it.size.width.toFloat(), it.size.height.toFloat())))
                // Query the current transform at click time, including an in-progress grid resize.
                // Partially clipped cells cannot supply a matching whole-image crop.
                if (kotlin.math.abs(clipped.left - complete.left) < 1f && kotlin.math.abs(clipped.top - complete.top) < 1f &&
                    kotlin.math.abs(clipped.right - complete.right) < 1f && kotlin.math.abs(clipped.bottom - complete.bottom) < 1f) clipped else null
            }
        }
    }
    var thumbnail by remember(item.uri) { mutableStateOf<Drawable?>(null) }
    DisposableEffect(item.uri, registry) { onDispose { coordinates[0] = null; registry?.remove(item.uri, owner) } }
    Box(modifier.fillMaxWidth().aspectRatio(1f).testTag("photo:${item.uri}")
        .semantics { if (selecting) this.selected = selected }
        .background(Color(0xFFECECF1))
        .onGloballyPositioned {
            coordinates[0] = it
            registry?.update(item.uri, owner, currentBounds, thumbnail)
        }
        .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)) {
        MediaImage(item, Modifier.fillMaxSize(), onError = { failed = true }, onSuccess = { failed = false },
            onDrawable = { thumbnail = it; registry?.update(item.uri, owner, currentBounds, it) })
        if (failed) Icon(Icons.Rounded.BrokenImage, "图片无法解码", Modifier.align(Alignment.Center).size(28.dp), tint = SecondaryInk.copy(.7f))
        if (badges && item.isAnimatedCandidate) {
            Row(Modifier.align(Alignment.BottomStart).padding(5.dp).clip(RoundedCornerShape(5.dp)).background(Color.Black.copy(.48f)).padding(horizontal = 5.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(11.dp), tint = Color.White)
                Text(item.name.substringAfterLast('.', "动图").uppercase().take(5), color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (item.isFavorite) Icon(Icons.Rounded.Favorite, "已收藏", Modifier.align(Alignment.BottomEnd).padding(5.dp).size(14.dp), tint = Color.White)
        if (selecting) {
            if (selected) Box(Modifier.fillMaxSize().background(AppleBlue.copy(.15f)).border(2.dp, AppleBlue))
            Icon(if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                Modifier.align(Alignment.TopEnd).padding(7.dp).size(25.dp).clip(CircleShape).background(Color.White.copy(.95f)),
                tint = if (selected) AppleBlue else SecondaryInk)
        }
    }
}

private data class Album(val id: String, val title: String, val photos: List<MediaItem>, val icon: ImageVector? = null, val color: Color = AppleBlue)

@Composable
private fun AlbumGrid(images: List<MediaItem>, columns: Int, state: LazyGridState, modifier: Modifier, onAlbum: (String, String) -> Unit) {
    val albums = remember(images) {
        listOf(
            Album(FAVORITES, "个人收藏", images.filter { it.isFavorite }, Icons.Rounded.Favorite, Color(0xFFFF375F)),
            Album(ANIMATIONS, "动图", images.filter { it.isAnimatedCandidate }, Icons.Rounded.MotionPhotosOn, Color(0xFFAF52DE))
        ) + images.groupBy { it.albumId }.map { (id, items) -> Album(id, items.first().albumName, items) }.sortedBy { it.title }
    }
    Box(modifier) {
    LazyVerticalGrid(GridCells.Fixed(columns), state = state, modifier = Modifier.fillMaxSize().testTag("album_grid").padding(horizontal = 20.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 130.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("我的图集", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Spacer(Modifier.weight(1f))
                Text("${albums.size} 个", color = SecondaryInk, fontSize = 12.sp)
            }
        }
        items(albums, key = { it.id }) { album ->
            Column(gridMotion().testTag("album:${album.id}").clickable { onAlbum(album.id, album.title) }) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(22.dp)).background(album.color.copy(.08f))) {
                    if (album.photos.isNotEmpty()) MediaImage(album.photos.first(), Modifier.fillMaxSize())
                    if (album.icon != null) {
                        Box(Modifier.align(Alignment.BottomStart).padding(12.dp).size(35.dp).clip(CircleShape).background(Color.White.copy(.9f)), contentAlignment = Alignment.Center) {
                            Icon(album.icon, null, Modifier.size(19.dp), tint = album.color)
                        }
                    } else Icon(Icons.Rounded.Folder, null, Modifier.align(Alignment.BottomStart).padding(12.dp).size(23.dp), tint = Color.White)
                    if (album.photos.isEmpty()) Icon(album.icon ?: Icons.Rounded.PhotoLibrary, null, Modifier.align(Alignment.Center).size(38.dp), tint = album.color.copy(.5f))
                }
                Text(album.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.dp))
                Text("${album.photos.size} 张照片", color = SecondaryInk, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Text("双指缩放，调整图集大小", color = SecondaryInk.copy(.65f), fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(top = 3.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
    }
    GridFastScroller(state, Modifier.align(Alignment.CenterEnd)) { index -> albums.getOrNull(index - 1)?.title.orEmpty() }
    }
}

internal fun dateOf(timestamp: Long): LocalDate = Instant.ofEpochMilli(timestamp.coerceAtLeast(0)).atZone(ZoneId.systemDefault()).toLocalDate()
internal fun dateLabel(date: LocalDate): String = when (date) {
    LocalDate.now() -> "今天"
    LocalDate.now().minusDays(1) -> "昨天"
    else -> date.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
}
internal fun matchesSearch(item: MediaItem, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    if (q == "收藏") return item.isFavorite
    if (q == "动图") return item.isAnimatedCandidate
    val date = dateOf(item.dateTaken)
    return listOf(item.name, item.albumName, item.mimeType, date.toString(), dateLabel(date)).any { it.lowercase().contains(q) }
}
