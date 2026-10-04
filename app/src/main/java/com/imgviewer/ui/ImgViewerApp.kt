package com.imgviewer.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.imgviewer.data.MediaItem
import com.imgviewer.editor.EditorScreen
import kotlinx.coroutines.launch

internal fun galleryPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    Build.VERSION.SDK_INT <= 28 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

@Composable
fun ImgViewerApp(externalUri: Uri?, onExternalHandled: () -> Unit) {
    val context = LocalContext.current
    val model: GalleryViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val actions: BatchActionsViewModel = viewModel()
    val viewerNavigation: ViewerNavigationViewModel = viewModel()
    val actionState by actions.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var viewerUri by rememberSaveable { mutableStateOf<String?>(null) }
    var viewerSingle by rememberSaveable { mutableStateOf(false) }
    var viewerVisible by rememberSaveable { mutableStateOf(false) }
    var viewerSession by rememberSaveable { mutableIntStateOf(0) }
    val transitionRegistry = remember { PhotoTransitionRegistry() }
    var editingUri by rememberSaveable { mutableStateOf<String?>(null) }
    var permissionVersion by remember { mutableIntStateOf(0) }
    var showPermissionHelp by remember { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    val darkUi = viewerUri != null || editingUri != null
    SideEffect {
        (context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = !darkUi
                isAppearanceLightNavigationBars = !darkUi
            }
        }
    }
    val fullAccess = remember(permissionVersion) {
        ContextCompat.checkSelfPermission(context,
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
    val partialAccess = remember(permissionVersion) {
        Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(context,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        permissionVersion++
        model.reload()
        if (results.values.none { it }) showPermissionHelp = true
    }
    val documentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val intent = result.data
            val uris = buildList {
                intent?.clipData?.let { clip -> for (i in 0 until clip.itemCount) add(clip.getItemAt(i).uri) }
                intent?.data?.let { add(it) }
            }.distinct()
            uris.forEach { uri ->
                val flags = (intent?.flags ?: 0) and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                runCatching {
                    when {
                        (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0 && (flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0 ->
                            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0 ->
                            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
            }
            model.importImages(uris)
        }
    }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        actions.receiveDeleteResult(result.resultCode)
    }
    val shareLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Receivers are not required to report whether a share was sent. Returning only advances
        // the batch picker; each later batch still requires an explicit tap from the user.
        actions.shareReturned()
    }
    fun launchShare() {
        val prepared = actionState.preparedShare ?: return
        if (!actions.claimShare()) return
        try {
            shareLauncher.launch(Intent.createChooser(prepared.intents[actionState.shareIndex], "分享照片"))
        } catch (failure: Exception) {
            actions.finishShare("无法打开分享：${failure.localizedMessage ?: "请稍后重试"}")
        }
    }
    LaunchedEffect(actionState.deletePrompt?.token) {
        actionState.deletePrompt?.let { prompt ->
            if (actions.claimDeletePrompt(prompt.token)) {
                try { deleteLauncher.launch(IntentSenderRequest.Builder(prompt.sender).build()) }
                catch (failure: Exception) { actions.receiveDeleteLaunchFailure(failure) }
            }
        }
    }
    LaunchedEffect(actionState.preparedShare) {
        if (actionState.preparedShare?.additionalIntents?.isEmpty() == true) launchShare()
    }
    LaunchedEffect(actionState.message) {
        actionState.message?.let { snack.showSnackbar(it); actions.clearMessage() }
    }
    fun openViewer(item: MediaItem, sequence: List<MediaItem>) {
        viewerNavigation.uris = sequence.map { it.uri.toString() }
        viewerSingle = sequence.size == 1
        viewerUri = item.uri.toString()
        viewerVisible = true
        viewerSession++
    }
    LaunchedEffect(externalUri) {
        externalUri?.let { uri ->
            model.openExternal(uri) { item -> openViewer(item, listOf(item)) }
            onExternalHandled()
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let { snack.showSnackbar(it); model.clearError() }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { permissionVersion++; model.onResume() }
            if (event == Lifecycle.Event.ON_PAUSE) model.onPause()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    // Keep the URI sequence through rotation. After process recreation only the current URI and
    // single-image mode are restored; the browsing sequence is rebuilt from the fresh library.
    val viewerItems = remember(state.images, viewerNavigation.uris, viewerUri, viewerSingle) {
        if (viewerUri == null) emptyList() else {
            val keys = viewerNavigation.uris.ifEmpty {
                if (viewerSingle) listOfNotNull(viewerUri) else state.images.map { it.uri.toString() }
            }.toSet()
            state.images.filter { it.uri.toString() in keys }
        }
    }
    LaunchedEffect(viewerItems, state.loading) {
        if (!state.loading && viewerUri != null && viewerItems.isEmpty()) viewerUri = null
    }
    ImgViewerTheme {
        Box(Modifier.fillMaxSize()) {
            GalleryScreen(
                state = state, fullAccess = fullAccess, partialAccess = partialAccess,
                onPermission = { permissionLauncher.launch(galleryPermissions()) },
                onImport = {
                    documentLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        type = "image/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/octet-stream"))
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        addCategory(Intent.CATEGORY_OPENABLE)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    })
                },
                onOpen = ::openViewer, onRefresh = model::reload, transitionRegistry = transitionRegistry,
                onDeleteSelected = actions::requestDelete, onShareSelected = actions::prepareShare,
                actionsBusy = actionState.busy, onAbout = { showAbout = true }
            )
            if (viewerUri != null && viewerItems.isNotEmpty()) {
                val session = viewerSession
                key(session) {
                    PhotoViewerTransition(items = viewerItems, initialUri = viewerUri!!,
                        visible = viewerVisible, registry = transitionRegistry,
                        onClosed = { if (viewerSession == session && !viewerVisible) {
                            viewerUri = null
                            viewerNavigation.uris = emptyList()
                        } },
                        onCloseRequest = { viewerVisible = false }, onFavorite = model::toggleFavorite,
                        onDelete = { actions.requestDelete(listOf(it)) }, onEdit = { editingUri = it.uri.toString() },
                        onCurrentUriChange = { viewerUri = it })
                }
            }
            editingUri?.let { uri ->
                val name = state.images.firstOrNull { it.uri.toString() == uri }?.name ?: "图片"
                EditorScreen(Uri.parse(uri), name, onClose = { editingUri = null }, onSaved = { saved ->
                    editingUri = null
                    model.openExternal(saved) { item -> openViewer(item, listOf(item)) }
                    scope.launch { snack.showSnackbar("已将编辑副本保存到 Pictures/ImgViewer") }
                })
            }
            // Register after the gallery; the editor owns Back while it saves.
            BackHandler(enabled = editingUri == null && viewerUri != null) { viewerVisible = false }
            if (showAbout) AboutScreen(onClose = { showAbout = false })
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
        }
        if (showPermissionHelp) AlertDialog(
            onDismissRequest = { showPermissionHelp = false },
            title = { Text("访问照片") },
            text = { Text("允许访问照片后可以浏览本机相册。也可以使用「选择文件」单独打开 WebP 或其他图片；若已关闭权限，请到应用设置中开启。") },
            confirmButton = { TextButton(onClick = {
                showPermissionHelp = false
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }) { Text("打开设置") } },
            dismissButton = { TextButton(onClick = { showPermissionHelp = false }) { Text("稍后") } }
        )
        actionState.confirmation?.let { items ->
            AlertDialog(onDismissRequest = actions::dismissDelete, modifier = Modifier.testTag("delete_confirmation"),
                title = { Text(if (items.size == 1) "删除这张照片？" else "删除这 ${items.size} 张照片？") },
                text = { Text(if (items.size == 1) "将删除原文件「${items.single().name}」。此操作无法在 ImgViewer 内撤销。"
                    else "将删除所选照片的原文件。此操作无法在 ImgViewer 内撤销。") },
                confirmButton = { TextButton(onClick = {
                    actions.confirmDelete(model::deletePrivateCopy, model::removedMany)
                }, modifier = Modifier.testTag("confirm_delete")) { Text("删除", color = androidx.compose.ui.graphics.Color(0xFFFF453A)) } },
                dismissButton = { TextButton(onClick = actions::dismissDelete, modifier = Modifier.testTag("cancel_delete")) { Text("取消") } }
            )
        }
        actionState.preparedShare?.takeIf { it.additionalIntents.isNotEmpty() }?.let { prepared ->
            AlertDialog(onDismissRequest = { actions.finishShare() },
                title = { Text("分批分享照片") },
                text = { Text("所选照片较多，将分 ${prepared.intents.size} 批分享。当前为第 ${actionState.shareIndex + 1} 批。") },
                confirmButton = { TextButton(onClick = ::launchShare, enabled = !actionState.shareOpened) { Text("选择 App") } },
                dismissButton = { TextButton(onClick = { actions.finishShare() }, enabled = !actionState.shareOpened) { Text("结束分享") } }
            )
        }
    }
}
