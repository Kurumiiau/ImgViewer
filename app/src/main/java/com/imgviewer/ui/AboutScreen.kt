package com.imgviewer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.imgviewer.BuildConfig

@Composable
internal fun AboutScreen(onClose: () -> Unit) {
    var privacy by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val policy = remember(context) {
        context.assets.open("PRIVACY.md").bufferedReader(Charsets.UTF_8).use { it.readText() }
            .split(Regex("\\r?\\n\\s*\\r?\\n")).drop(1)
    }
    fun goBack() { if (privacy) privacy = false else onClose() }
    BackHandler { goBack() }
    Column(Modifier.fillMaxSize().background(Paper).statusBarsPadding().navigationBarsPadding()
        .testTag(if (privacy) "privacy_page" else "about_page")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ::goBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = AppleBlue)
            }
            Text(if (privacy) "隐私协议" else "关于 ImgViewer", fontSize = 23.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
        }
        if (privacy) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(policy) { paragraph ->
                    val heading = paragraph.startsWith("## ")
                    Text(paragraph.removePrefix("## "), fontSize = if (heading) 18.sp else 14.sp,
                        lineHeight = 23.sp, fontWeight = if (heading) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (heading) Ink else SecondaryInk)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)) {
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(82.dp).clip(RoundedCornerShape(23.dp)).background(AppleBlue),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(46.dp), tint = Color.White)
                        }
                        Text("ImgViewer", fontSize = 28.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 18.dp))
                        Text("版本 ${BuildConfig.VERSION_NAME}", color = SecondaryInk, fontSize = 13.sp,
                            modifier = Modifier.padding(top = 6.dp))
                        Text("作者：Kurumiiau", fontSize = 16.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 18.dp))
                        Text("让手机轻松查看 WEBP 动图", color = SecondaryInk, fontSize = 14.sp,
                            modifier = Modifier.padding(top = 10.dp))
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color.White)
                        .clickable { privacy = true }.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.PrivacyTip, null, tint = AppleBlue)
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text("隐私协议", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text("了解照片、权限与本地数据的处理方式", fontSize = 12.sp, color = SecondaryInk,
                                modifier = Modifier.padding(top = 5.dp))
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = SecondaryInk)
                    }
                }
                item {
                    Text("WEBP / GIF 动图 · 照片与图集 · 本地图片编辑", color = SecondaryInk,
                        fontSize = 12.sp, lineHeight = 20.sp)
                }
            }
        }
    }
}
