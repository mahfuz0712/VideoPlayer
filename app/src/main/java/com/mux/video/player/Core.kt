package com.mux.video.player

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class Video(
    val id: Long, val title: String, val durMs: Long, val width: Int, val height: Int,
    val size: Long, val path: String, val added: Long
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
    val folder: String get() = path.substringBeforeLast('/', "")
    val res: String
        get() {
            val m = minOf(width, height)
            return when {
                m <= 0 -> ""
                m >= 2000 -> "4K"
                m >= 1400 -> "1440p"
                m >= 1000 -> "1080p"
                m >= 700 -> "720p"
                m >= 460 -> "480p"
                else -> "${m}p"
            }
        }
}

fun scanVideos(ctx: Context, minSec: Int): List<Video> {
    val m = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val proj = arrayOf(
        MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DATA, MediaStore.Video.Media.DATE_ADDED
    )
    val out = ArrayList<Video>()
    ctx.contentResolver.query(
        m, proj, "${MediaStore.Video.Media.DURATION} >= ?", arrayOf((minSec * 1000L).toString()), null
    )?.use { c ->
        while (c.moveToNext()) {
            val name = c.getString(1) ?: "Video"
            out += Video(
                c.getLong(0), name.substringBeforeLast('.'), c.getLong(2), c.getInt(3), c.getInt(4),
                c.getLong(5), c.getString(6) ?: "", c.getLong(7)
            )
        }
    }
    return out
}

fun sortVideos(l: List<Video>, by: Int, desc: Boolean): List<Video> {
    val s = when (by) {
        0 -> l.sortedBy { it.title.lowercase() }
        1 -> l.sortedBy { it.added }
        2 -> l.sortedBy { it.size }
        else -> l.sortedBy { it.durMs }
    }
    return if (desc) s.reversed() else s
}

private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "ts", "flv", "wmv")

/** Asks Android to index video files in already-known folders that are not in the media library yet. */
suspend fun indexNewFiles(ctx: Context, known: List<Video>): Unit = withContext(Dispatchers.IO) {
    val knownPaths = known.mapTo(HashSet()) { it.path }
    val files = known.map { it.folder }.toSet().flatMap { dir ->
        File(dir).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in VIDEO_EXT && it.absolutePath !in knownPaths }
            ?.map { it.absolutePath }
            ?: emptyList()
    }
    if (files.isEmpty()) return@withContext
    val latch = CountDownLatch(files.size)
    MediaScannerConnection.scanFile(ctx, files.toTypedArray(), null) { _, _ -> latch.countDown() }
    latch.await(8, TimeUnit.SECONDS)
    Unit
}

object Store {
    private lateinit var sp: SharedPreferences
    var accent by mutableIntStateOf(0)
    var minSec by mutableIntStateOf(10)
    var resume by mutableStateOf(true)
    var autoNext by mutableStateOf(true)
    var seekStep by mutableIntStateOf(10)
    var sortBy by mutableIntStateOf(1)
    var sortDesc by mutableStateOf(true)
    var tick by mutableIntStateOf(0)
    var videos by mutableStateOf<List<Video>>(emptyList())

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences("mux_video", Context.MODE_PRIVATE)
        accent = sp.getInt("accent", 0)
        minSec = sp.getInt("minSec", 10)
        resume = sp.getBoolean("resume", true)
        autoNext = sp.getBoolean("autoNext", true)
        seekStep = sp.getInt("seekStep", 10)
        sortBy = sp.getInt("sortBy", 1)
        sortDesc = sp.getBoolean("sortDesc", true)
    }

    fun updateAccent(i: Int) { accent = i; sp.edit().putInt("accent", i).apply() }
    fun updateMinSec(s: Int) { minSec = s; sp.edit().putInt("minSec", s).apply() }
    fun updateResume(b: Boolean) { resume = b; sp.edit().putBoolean("resume", b).apply() }
    fun updateAutoNext(b: Boolean) { autoNext = b; sp.edit().putBoolean("autoNext", b).apply() }
    fun updateSeekStep(s: Int) { seekStep = s; sp.edit().putInt("seekStep", s).apply() }
    fun updateSort(by: Int, desc: Boolean) {
        sortBy = by; sortDesc = desc
        sp.edit().putInt("sortBy", by).putBoolean("sortDesc", desc).apply()
    }

    fun savedPos(id: Long): Long = sp.getLong("p$id", 0L)
    fun savePos(id: Long, pos: Long, dur: Long) {
        val e = sp.edit()
        if (dur > 0 && (pos < 3000 || pos > dur - 5000)) e.remove("p$id") else e.putLong("p$id", pos)
        e.apply()
        tick++
    }
}
