package com.mux.video.player

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Edit these values. */
object AppInfo {
    const val DEV_NAME = "Mohammad Mahfuz Rahman"
    const val DEV_EMAIL = "mahfuzrahman0712@gmail.com"
    const val GITHUB_USER = "mahfuz0712"
    const val REPO = "VideoPlayer"
    const val MORE_APPS = "https://zappsstore-frontend.onrender.com"
}

class UpdateInfo(val version: String, val apkUrl: String, val notes: String)

fun appVersion(ctx: Context): String = try {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0.0.0"
} catch (e: Exception) { "0.0.0" }

fun isOnline(ctx: Context): Boolean {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

private fun parts(v: String): List<Int> =
    v.trim().removePrefix("v").removePrefix("V").split('.', '-').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

fun isNewer(remote: String, local: String): Boolean {
    val a = parts(remote)
    val b = parts(local)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

/** Returns (requestWorked, newerReleaseOrNull). */
suspend fun checkUpdate(ctx: Context): Pair<Boolean, UpdateInfo?> = withContext(Dispatchers.IO) {
    try {
        val url = "https://api.github.com/repos/${AppInfo.GITHUB_USER}/${AppInfo.REPO}/releases/latest"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        if (c.responseCode != 200) return@withContext false to null
        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val tag = j.optString("tag_name")
        if (tag.isEmpty() || !isNewer(tag, appVersion(ctx))) return@withContext true to null
        var apk = j.optString("html_url")
        val assets = j.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", true)) {
                    apk = a.optString("browser_download_url")
                    break
                }
            }
        }
        true to UpdateInfo(tag.removePrefix("v"), apk, j.optString("body").take(300))
    } catch (e: Exception) {
        false to null
    }
}

fun openUrl(ctx: Context, url: String) {
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { }
}

fun openMail(ctx: Context, addr: String) {
    try { ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$addr"))) } catch (e: Exception) { }
}

@Composable
fun UpdateDialog(u: UpdateInfo, current: String, onUpdate: () -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel) {
        Column(Modifier.fillMaxWidth().background(Color(0xFF050D12)).border(1.dp, cMain).padding(20.dp)) {
            T("// SYSTEM UPDATE DETECTED", 12f, cSub)
            T("UPDATE AVAILABLE", 18f, cMain, true, Modifier.padding(top = 6.dp))
            T("v$current  >>  v${u.version}", 14f, Color.White, mod = Modifier.padding(top = 10.dp))
            if (u.notes.isNotBlank()) T(u.notes, 11f, cSub, mod = Modifier.padding(top = 10.dp), lines = 6)
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("CANCEL", false, Modifier.weight(1f), onCancel)
                Chip("UPDATE", true, Modifier.weight(1f), onUpdate)
            }
        }
    }
}
