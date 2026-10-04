package com.imgviewer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.imgviewer.ui.ImgViewerApp

class MainActivity : ComponentActivity() {
    private var openedUri by mutableStateOf<Uri?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openedUri = intent.takeIf { it.action == Intent.ACTION_VIEW }?.data
        setContent { ImgViewerApp(openedUri) { openedUri = null } }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openedUri = intent.takeIf { it.action == Intent.ACTION_VIEW }?.data
    }
}
