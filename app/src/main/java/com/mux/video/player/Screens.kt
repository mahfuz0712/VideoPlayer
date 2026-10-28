package com.mux.video.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

fun fmt(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}

fun fmtSize(b: Long): String = when {
    b >= (1L shl 30) -> "%.2f GB".format(b / 1073741824.0)
    b >= (1L shl 20) -> "%.0f MB".format(b / 1048576.0)
    else -> "${b / 1024} KB"
}

@Composable
fun VideoRow(v: Video, active: Boolean, onClick: () -> Unit) {
    val saved = remember(Store.tick, v.id) { Store.savedPos(v.id) }
    Row(
        Modifier.fillMaxWidth().background(if (active) cDim.copy(alpha = 0.35f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(124.dp).aspectRatio(16f / 9f).brackets(cMain.copy(alpha = 0.7f))) {
            Thumb(v, Modifier.fillMaxSize().padding(2.dp))
            T(
                fmt(v.durMs), 10f, Color.White,
                mod = Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color(0xAA000000)).padding(horizontal = 4.dp, vertical = 1.dp)
            )
            if (saved > 0 && v.durMs > 0) {
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth((saved.toFloat() / v.durMs).coerceIn(0f, 1f))
                        .height(3.dp).background(cMain)
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            T(v.title, 14f, if (active) cMain else Color(0xFFD8F3F7), active, lines = 2)
            T(listOf(v.res, fmtSize(v.size)).filter { it.isNotEmpty() }.joinToString(" · "), 11f, cSub, mod = Modifier.padding(top = 2.dp))
            T(v.folder.substringAfterLast('/'), 10f, cSub)
        }
    }
}

@Composable
fun VideosScreen(folder: String?, scanning: Boolean, onRefresh: () -> Unit, onClearFolder: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val shown = remember(Store.videos, folder, q, Store.sortBy, Store.sortDesc) {
        sortVideos(
            Store.videos.filter {
                (folder == null || it.folder == folder) &&
                    (q.isBlank() || it.title.contains(q, true) || it.folder.contains(q, true))
            },
            Store.sortBy, Store.sortDesc
        )
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 6.dp).border(1.dp, cDim).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            T("> ", 15f, cMain, true)
            Box(Modifier.weight(1f)) {
                if (q.isEmpty()) T("SEARCH_VIDEOS...", 14f, cSub)
                BasicTextField(
                    q, { q = it }, singleLine = true,
                    textStyle = TextStyle(color = cMain, fontFamily = Mono, fontSize = 15.sp),
                    cursorBrush = SolidColor(cMain), modifier = Modifier.fillMaxWidth()
                )
            }
            if (q.isNotEmpty()) T("[X]", 13f, cMain, mod = Modifier.clickable { q = "" })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("NAME", "DATE", "SIZE", "LENGTH").forEachIndexed { i, n ->
                Chip(n, Store.sortBy == i, Modifier.weight(1f)) { Store.updateSort(i, if (Store.sortBy == i) !Store.sortDesc else Store.sortDesc) }
            }
            Chip(if (Store.sortDesc) "DESC" else "ASC", false, Modifier.width(64.dp)) { Store.updateSort(Store.sortBy, !Store.sortDesc) }
        }
        if (folder != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                T("DIR: ${folder.substringAfterLast('/')}", 12f, cMain, mod = Modifier.weight(1f))
                T("[CLEAR]", 12f, cMain, mod = Modifier.clickable(onClick = onClearFolder).padding(6.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            T(if (scanning) "// SCANNING..." else "// ${shown.size} VIDEOS", 11f, cSub, mod = Modifier.weight(1f))
            T("[REFRESH]", 12f, cMain, mod = Modifier.clickable(onClick = onRefresh).padding(6.dp))
        }
        if (shown.isEmpty() && !scanning) T("NO VIDEOS FOUND", 13f, cSub, mod = Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(shown, key = { _, v -> v.id }) { i, v ->
                VideoRow(v, false) { Playback.open(shown, i) }
            }
        }
    }
}

@Composable
fun FoldersScreen(onPick: (String) -> Unit) {
    val dirs = remember(Store.videos) {
        Store.videos.groupBy { it.folder }.map { it.key to it.value.size }.sortedBy { it.first.lowercase() }
    }
    Column(Modifier.fillMaxSize()) {
        T("// ${dirs.size} DIRECTORIES", 11f, cSub, mod = Modifier.padding(16.dp, 12.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(dirs, key = { _, d -> d.first }) { _, d ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(d.first) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        T(d.first.substringAfterLast('/').ifEmpty { "/" }, 15f, Color(0xFFD8F3F7), true)
                        T(d.first, 10f, cSub)
                    }
                    T("${d.second}", 13f, cMain)
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(scanning: Boolean, onRescan: () -> Unit, onUpdateFound: (UpdateInfo) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    @Composable fun section(t: String) = T("// $t", 12f, cSub, true, Modifier.padding(top = 22.dp, bottom = 8.dp))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        T("SYSTEM CONFIG", 16f, cMain, true)

        section("INTERFACE COLOR")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ACCENTS.forEachIndexed { i, a -> Chip(a.name, Store.accent == i, Modifier.weight(1f)) { Store.updateAccent(i) } }
        }

        section("IGNORE VIDEOS SHORTER THAN")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((l, v) in listOf("ALL" to 0, "10s" to 10, "30s" to 30, "60s" to 60))
                Chip(l, Store.minSec == v, Modifier.weight(1f)) { Store.updateMinSec(v) }
        }

        section("DOUBLE TAP / SEEK STEP")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (v in listOf(5, 10, 15, 30)) Chip("${v}s", Store.seekStep == v, Modifier.weight(1f)) { Store.updateSeekStep(v) }
        }

        section("RESUME WHERE YOU LEFT OFF")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("ON", Store.resume, Modifier.weight(1f)) { Store.updateResume(true) }
            Chip("OFF", !Store.resume, Modifier.weight(1f)) { Store.updateResume(false) }
        }

        section("PLAY NEXT VIDEO AUTOMATICALLY")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("ON", Store.autoNext, Modifier.weight(1f)) { Store.updateAutoNext(true) }
            Chip("OFF", !Store.autoNext, Modifier.weight(1f)) { Store.updateAutoNext(false) }
        }

        section("LIBRARY")
        Chip(if (scanning) "SCANNING..." else "RESCAN LIBRARY", false, Modifier.fillMaxWidth()) { onRescan() }
        T("${Store.videos.size} videos indexed", 12f, cSub, mod = Modifier.padding(top = 8.dp))

        section("UPDATES")
        Chip("CHECK FOR UPDATES", false, Modifier.fillMaxWidth()) {
            scope.launch {
                status = "CHECKING..."
                if (!isOnline(ctx)) { status = "NO INTERNET CONNECTION"; return@launch }
                val (ok, info) = checkUpdate(ctx)
                status = when {
                    !ok -> "CHECK FAILED"
                    info == null -> "UP TO DATE"
                    else -> "NEW VERSION AVAILABLE: v${info.version}"
                }
                if (info != null) onUpdateFound(info)
            }
        }
        if (status.isNotEmpty()) T(status, 12f, cSub, mod = Modifier.padding(top = 8.dp), lines = 2)

        section("ABOUT")
        T("VIDEO PLAYER v${appVersion(ctx)}", 13f, cMain, true)
        T("VIDEO CORE: MUX", 11f, cSub, mod = Modifier.padding(top = 4.dp))
        T("SOURCE: LOCAL FILES ONLY", 11f, cSub, mod = Modifier.padding(top = 2.dp, bottom = 10.dp))
        T("DEV: ${AppInfo.DEV_NAME}", 12f, Color(0xFFD8F3F7))
        T("MAIL: ${AppInfo.DEV_EMAIL}", 12f, cMain, mod = Modifier.clickable { openMail(ctx, AppInfo.DEV_EMAIL) }.padding(vertical = 6.dp))
        T("GITHUB: github.com/${AppInfo.GITHUB_USER}", 12f, cMain, mod = Modifier.clickable { openUrl(ctx, "https://github.com/${AppInfo.GITHUB_USER}") }.padding(vertical = 6.dp), lines = 2)

        section("MORE APPS")
        T("zApps Store >>", 13f, cMain, true, Modifier.clickable { openUrl(ctx, AppInfo.MORE_APPS) }.padding(vertical = 6.dp))
        T("zappsstore-frontend.onrender.com", 11f, cSub, lines = 2)
    }
}
