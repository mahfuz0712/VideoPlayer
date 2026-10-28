package com.mux.video.player

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT)
        )
        Store.init(this)
        setContent { App() }
    }

    // Leaving the app while a video plays -> floating picture-in-picture window
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Playback.screenActive && Playback.isPlaying) enterPip(this)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        Playback.inPip = isInPictureInPictureMode
    }

    override fun onDestroy() {
        Playback.close()
        super.onDestroy()
    }
}

enum class Screen(val label: String) { VIDEOS("VIDEOS"), FOLDERS("DIRS"), SETTINGS("SYS") }

private fun perm(): String =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

private fun hasPerm(c: Context) = androidx.core.content.ContextCompat.checkSelfPermission(c, perm()) == PackageManager.PERMISSION_GRANTED

@Composable
fun App() {
    val ctx = LocalContext.current
    var screen by remember { mutableStateOf(Screen.VIDEOS) }
    var folder by remember { mutableStateOf<String?>(null) }
    var granted by remember { mutableStateOf(hasPerm(ctx)) }
    var scanning by remember { mutableStateOf(false) }
    var rescan by remember { mutableIntStateOf(0) }
    var deepScan by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = hasPerm(ctx) }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(perm()) }

    LaunchedEffect(granted, Store.minSec, rescan) {
        if (granted) {
            scanning = true
            if (deepScan) {
                deepScan = false
                indexNewFiles(ctx, Store.videos)
            }
            val fresh = withContext(Dispatchers.IO) { scanVideos(ctx, Store.minSec) }
            if (fresh != Store.videos) Store.videos = fresh
            scanning = false
        }
    }
    LaunchedEffect(Unit) { if (isOnline(ctx)) update = checkUpdate(ctx).second }

    // Auto refresh when the Android video library changes (new download, delete, ...)
    DisposableEffect(granted) {
        if (!granted) return@DisposableEffect onDispose { }
        val handler = Handler(Looper.getMainLooper())
        val run = Runnable { rescan++ }
        val obs = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                handler.removeCallbacks(run)
                handler.postDelayed(run, 1500)
            }
        }
        ctx.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, obs)
        onDispose {
            ctx.contentResolver.unregisterContentObserver(obs)
            handler.removeCallbacks(run)
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) rescan++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        update?.let { u ->
            UpdateDialog(u, appVersion(ctx), onUpdate = { openUrl(ctx, u.apkUrl); update = null }, onCancel = { update = null })
        }
        HudBackground {
            if (Playback.screenActive) {
                PlayerScreen()
            } else {
                Column(Modifier.fillMaxSize().systemBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            T("VIDEO PLAYER", 20f, cMain, true)
                            T("// MUX VIDEO CORE", 11f, cSub)
                        }
                        T("[ ${Store.videos.size} ]", 11f, cSub)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(cDim))
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        val needsPerm = screen == Screen.VIDEOS || screen == Screen.FOLDERS
                        when {
                            !granted && needsPerm -> Column(
                                Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally
                            ) {
                                T("ACCESS DENIED", 18f, cMain, true)
                                T("Video permission is required to index local videos.", 12f, cSub, mod = Modifier.padding(vertical = 12.dp), lines = 3, align = TextAlign.Center)
                                Chip("GRANT ACCESS", true, Modifier.fillMaxWidth()) { launcher.launch(perm()) }
                                Spacer(Modifier.height(8.dp))
                                Chip("OPEN APP SETTINGS", false, Modifier.fillMaxWidth()) {
                                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                                }
                            }
                            screen == Screen.VIDEOS -> VideosScreen(folder, scanning, { deepScan = true; rescan++ }) { folder = null }
                            screen == Screen.FOLDERS -> FoldersScreen { folder = it; screen = Screen.VIDEOS }
                            else -> SettingsScreen(scanning, { deepScan = true; rescan++ }, { update = it })
                        }
                    }
                    Row(Modifier.fillMaxWidth().background(Color(0xFF050D12))) {
                        for (s in Screen.entries) {
                            Column(
                                Modifier.weight(1f).clickable { screen = s }.padding(vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                T(s.label, 12f, if (screen == s) cMain else cSub, screen == s)
                                Box(Modifier.padding(top = 4.dp).width(24.dp).height(2.dp).background(if (screen == s) cMain else Color.Transparent))
                            }
                        }
                    }
                }
            }
        }
    }
}
