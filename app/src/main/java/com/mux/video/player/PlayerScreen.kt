package com.mux.video.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Rational
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Holds what the player screen needs to share with the Activity. */
object Playback {
    var screenActive by mutableStateOf(false)
    var inPip by mutableStateOf(false)
    var queue by mutableStateOf<List<Video>>(emptyList())
    var startIndex = 0
    var player: ExoPlayer? = null
    @Volatile var isPlaying = false
    @Volatile var vw = 16
    @Volatile var vh = 9

    fun open(list: List<Video>, index: Int) {
        queue = list
        startIndex = index
        screenActive = true
    }

    fun close() {
        player?.let { p ->
            queue.getOrNull(p.currentMediaItemIndex)?.let { Store.savePos(it.id, p.currentPosition, p.duration) }
            p.release()
        }
        player = null
        isPlaying = false
        inPip = false
        screenActive = false
    }
}

fun enterPip(a: Activity) {
    if (Build.VERSION.SDK_INT < 26) return
    val ar = (Playback.vw.toFloat() / Playback.vh).coerceIn(0.5f, 2.2f)
    try {
        a.enterPictureInPictureMode(
            PictureInPictureParams.Builder().setAspectRatio(Rational((ar * 100).toInt(), 100)).build()
        )
    } catch (e: Exception) { }
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

fun Video.toItem(): MediaItem = MediaItem.Builder()
    .setUri(uri).setMediaId(id.toString())
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
    .build()

private fun spd(f: Float): String = if (f % 1f == 0f) "${f.toInt()}.0x" else "${f}x"

private fun resumeStart(v: Video): Long {
    val s = Store.savedPos(v.id)
    return if (s > 3000 && s < v.durMs - 5000) s else 0L
}

private fun trackName(f: Format, i: Int): String {
    val lang = f.language?.takeIf { it != "und" && it.isNotBlank() }
        ?.let { Locale.forLanguageTag(it).displayLanguage.ifBlank { it } }
    val ch = if (f.channelCount > 0) "${f.channelCount}ch" else null
    return listOfNotNull(f.label, lang, ch).joinToString(" · ").ifBlank { "Track ${i + 1}" }
}

@Composable
private fun Tool(label: String, onClick: () -> Unit) =
    T("[$label]", 11f, cMain, mod = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp))

@Composable
private fun MenuTool(label: String, items: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Tool(label) { open = true }
        DropdownMenu(
            expanded = open, onDismissRequest = { open = false },
            modifier = Modifier.background(Color(0xFF06141A)).border(1.dp, cMain)
        ) {
            items.forEachIndexed { i, (name, sel) ->
                DropdownMenuItem(
                    text = { T(name, 13f, if (sel) cMain else cSub, sel) },
                    onClick = { onPick(i); open = false }
                )
            }
        }
    }
}

@Composable
fun PlayerScreen() {
    val ctx = LocalContext.current
    val activity = remember { ctx.findActivity() }
    val view = LocalView.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val inPip = Playback.inPip
    val full = landscape || inPip
    val queue = Playback.queue

    // ---------------- player ----------------
    val exo = remember {
        ExoPlayer.Builder(ctx)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build().also { p ->
                Playback.player = p
                val first = queue.getOrNull(Playback.startIndex)
                p.setMediaItems(
                    queue.map { it.toItem() }, Playback.startIndex,
                    if (first != null && Store.resume) resumeStart(first) else 0L
                )
                p.pauseAtEndOfMediaItems = !Store.autoNext
                p.prepare()
                p.playWhenReady = true
            }
    }

    var index by remember { mutableIntStateOf(Playback.startIndex) }
    var playing by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var vAr by remember { mutableFloatStateOf(16f / 9f) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }

    DisposableEffect(exo) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying; Playback.isPlaying = isPlaying }
            override fun onPlaybackStateChanged(playbackState: Int) { buffering = playbackState == Player.STATE_BUFFERING }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                index = exo.currentMediaItemIndex
                error = null
                if (Store.resume && (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)) {
                    queue.getOrNull(exo.currentMediaItemIndex)?.let { v ->
                        val s = resumeStart(v)
                        if (s > 0) exo.seekTo(s)
                    }
                }
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    vAr = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    Playback.vw = videoSize.width
                    Playback.vh = videoSize.height
                }
            }
            override fun onTracksChanged(t: Tracks) { tracks = t }
            override fun onPlayerError(e: PlaybackException) { error = e.errorCodeName }
        }
        exo.addListener(l)
        onDispose { exo.removeListener(l) }
    }

    LaunchedEffect(exo) {
        var n = 0
        while (true) {
            pos = exo.currentPosition
            dur = exo.duration.coerceAtLeast(0L)
            if (++n % 4 == 0) queue.getOrNull(exo.currentMediaItemIndex)?.let { Store.savePos(it.id, pos, dur) }
            kotlinx.coroutines.delay(250)
        }
    }

    // pause when the app goes to the background (but not when it moved into picture-in-picture)
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP && activity?.isInPictureInPictureMode != true) exo.pause()
        }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }

    // cleanup when leaving the player screen
    DisposableEffect(Unit) {
        onDispose {
            Playback.close()
            activity?.let { a ->
                a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                val lp = a.window.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                a.window.attributes = lp
                WindowCompat.getInsetsController(a.window, view).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // ---------------- rotation: follows the phone's auto-rotate setting ----------------
    var rotMode by remember { mutableIntStateOf(0) } // 0 AUTO, 1 LANDSCAPE, 2 PORTRAIT
    LaunchedEffect(rotMode) {
        activity?.requestedOrientation = when (rotMode) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            2 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
    }

    // hide system bars in fullscreen
    LaunchedEffect(landscape, inPip) {
        val w = activity?.window ?: return@LaunchedEffect
        val c = WindowCompat.getInsetsController(w, view)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (landscape || inPip) c.hide(WindowInsetsCompat.Type.systemBars()) else c.show(WindowInsetsCompat.Type.systemBars())
    }

    // ---------------- brightness / volume ----------------
    var brightness by remember {
        mutableFloatStateOf(
            run {
                val cur = activity?.window?.attributes?.screenBrightness ?: -1f
                if (cur >= 0f) cur else try {
                    Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
                } catch (e: Exception) { 0.5f }
            }
        )
    }
    fun applyBrightness(v: Float) {
        brightness = v.coerceIn(0.02f, 1f)
        activity?.window?.let { w -> val lp = w.attributes; lp.screenBrightness = brightness; w.attributes = lp }
    }
    val am = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVol = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var vol by remember { mutableFloatStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol) }
    fun applyVol(f: Float) {
        vol = f.coerceIn(0f, 1f)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (vol * maxVol).roundToInt(), 0)
    }

    // ---------------- ui state ----------------
    var show by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var touch by remember { mutableIntStateOf(0) }
    var hud by remember { mutableStateOf<String?>(null) }
    var aspect by remember { mutableIntStateOf(0) } // 0 FIT, 1 FILL, 2 ZOOM
    var speed by remember { mutableFloatStateOf(1f) }
    var repeat by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var drag by remember { mutableStateOf<Float?>(null) }
    val step = Store.seekStep * 1000L

    LaunchedEffect(show, playing, touch) { if (show && playing) { kotlinx.coroutines.delay(3500); show = false } }
    LaunchedEffect(hud) { if (hud != null) { kotlinx.coroutines.delay(900); hud = null } }

    fun seekBy(ms: Long) {
        val d = exo.duration.coerceAtLeast(0L)
        exo.seekTo((exo.currentPosition + ms).coerceIn(0L, if (d > 0) d else Long.MAX_VALUE))
    }

    BackHandler(true) { if (locked) { show = true; hud = "SCREEN LOCKED" } else Playback.close() }

    // load an external subtitle file the user picks
    val subPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val name: String = (try {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            } catch (e: Exception) { null }) ?: ""
            val mime = when (name.substringAfterLast('.', "").lowercase()) {
                "vtt" -> MimeTypes.TEXT_VTT
                "ass", "ssa" -> MimeTypes.TEXT_SSA
                "ttml", "xml" -> MimeTypes.APPLICATION_TTML
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            val idx = exo.currentMediaItemIndex
            queue.getOrNull(idx)?.let { v ->
                val sub = MediaItem.SubtitleConfiguration.Builder(uri)
                    .setMimeType(mime).setLanguage("und").setLabel(name.ifBlank { "Subtitle" })
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
                val p = exo.currentPosition
                exo.replaceMediaItem(idx, v.toItem().buildUpon().setSubtitleConfigurations(listOf(sub)).build())
                exo.seekTo(idx, p)
                exo.play()
                hud = "SUBTITLE LOADED"
            }
        }
    }

    val video = queue.getOrNull(index)
    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }

    // the row of tools: used in the overlay (landscape) or below the video (portrait)
    val tools: @Composable () -> Unit = {
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Tool("LOCK") { locked = true; show = false }
            Tool("ROT:" + listOf("AUTO", "LAND", "PORT")[rotMode]) { rotMode = (rotMode + 1) % 3; touch++ }
            Tool(listOf("FIT", "FILL", "ZOOM")[aspect]) { aspect = (aspect + 1) % 3; touch++ }
            MenuTool(
                spd(speed),
                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).map { spd(it) to (it == speed) }
            ) { i ->
                speed = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)[i]
                exo.setPlaybackSpeed(speed)
                touch++
            }
            MenuTool(
                "SUB",
                listOf("OFF" to textGroups.none { it.isSelected }) +
                    textGroups.mapIndexed { i, g -> trackName(g.getTrackFormat(0), i) to g.isSelected } +
                    listOf("LOAD FILE..." to false)
            ) { i ->
                val params = exo.trackSelectionParameters.buildUpon()
                when {
                    i == 0 -> exo.trackSelectionParameters = params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                    i <= textGroups.size -> exo.trackSelectionParameters = params
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(textGroups[i - 1].mediaTrackGroup, 0)).build()
                    else -> subPicker.launch(arrayOf("*/*"))
                }
                touch++
            }
            MenuTool(
                "AUD",
                if (audioGroups.isEmpty()) listOf("NO TRACKS" to false)
                else audioGroups.mapIndexed { i, g -> trackName(g.getTrackFormat(0), i) to g.isSelected }
            ) { i ->
                audioGroups.getOrNull(i)?.let { g ->
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
                }
                touch++
            }
            Tool("LOOP:" + listOf("OFF", "ONE", "ALL")[repeat]) {
                repeat = (repeat + 1) % 3
                exo.repeatMode = when (repeat) { 1 -> Player.REPEAT_MODE_ONE; 2 -> Player.REPEAT_MODE_ALL; else -> Player.REPEAT_MODE_OFF }
                touch++
            }
            if (Build.VERSION.SDK_INT >= 26) Tool("PIP") { activity?.let { enterPip(it) } }
        }
    }

    // ---------------- layout ----------------
    Column(Modifier.fillMaxSize().then(if (full) Modifier else Modifier.statusBarsPadding())) {
        Box(
            (if (full) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(vAr.coerceIn(0.75f, 1.78f)))
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { c ->
                    PlayerView(c).apply {
                        useController = false
                        player = exo
                        keepScreenOn = true
                        subtitleView?.setStyle(
                            CaptionStyleCompat(
                                android.graphics.Color.WHITE, android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT,
                                CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK, null
                            )
                        )
                    }
                },
                update = {
                    it.resizeMode = when (aspect) {
                        1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (!inPip) {
                // gestures: tap = show/hide, double tap = seek, drag = seek / brightness / volume
                Box(
                    Modifier.fillMaxSize()
                        .pointerInput(locked) {
                            detectTapGestures(
                                onTap = { show = !show },
                                onDoubleTap = { o ->
                                    if (!locked) {
                                        if (o.x > size.width / 2) { seekBy(step); hud = "+${Store.seekStep}s >>" }
                                        else { seekBy(-step); hud = "<< -${Store.seekStep}s" }
                                    }
                                }
                            )
                        }
                        .pointerInput(locked) {
                            if (locked) return@pointerInput
                            var mode = 0 // 1 seek, 2 brightness, 3 volume
                            var startX = 0f
                            var target = 0L
                            detectDragGestures(
                                onDragStart = { o -> mode = 0; startX = o.x },
                                onDragEnd = { if (mode == 1) exo.seekTo(target); mode = 0 },
                                onDragCancel = { mode = 0 },
                                onDrag = { change, d ->
                                    change.consume()
                                    if (mode == 0) {
                                        mode = if (abs(d.x) > abs(d.y)) 1 else if (startX < size.width / 2) 2 else 3
                                        if (mode == 1) target = exo.currentPosition
                                    }
                                    when (mode) {
                                        1 -> {
                                            val max = exo.duration.coerceAtLeast(0L)
                                            target = (target + (d.x / size.width * 90_000f).toLong()).coerceIn(0L, max)
                                            hud = "SEEK  ${fmt(target)} / ${fmt(max)}"
                                        }
                                        2 -> { applyBrightness(brightness - d.y / size.height * 1.2f); hud = "BRIGHTNESS  ${(brightness * 100).roundToInt()}%" }
                                        3 -> { applyVol(vol - d.y / size.height * 1.2f); hud = "VOLUME  ${(vol * 100).roundToInt()}%" }
                                    }
                                }
                            )
                        }
                )

                if (buffering && error == null) T("// BUFFERING...", 12f, cSub, mod = Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
                error?.let { T("PLAYBACK ERROR: $it", 13f, Color(0xFFFF6B6B), true, Modifier.align(Alignment.Center).padding(16.dp), 3, TextAlign.Center) }
                hud?.let {
                    T(
                        it, 14f, cMain, true,
                        Modifier.align(Alignment.Center).background(Color(0xCC000000)).border(1.dp, cDim).padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }

                if (locked) {
                    if (show) Chip("UNLOCK", true, Modifier.align(Alignment.Center).width(130.dp).background(Color(0xCC000000))) { locked = false; show = true; touch++ }
                } else if (show) {
                    Box(
                        Modifier.fillMaxSize().background(Color(0x77000000))
                            .then(if (landscape) Modifier.displayCutoutPadding() else Modifier)
                    ) {
                        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            T("<", 22f, cMain, true, Modifier.clickable { Playback.close() }.padding(horizontal = 12.dp, vertical = 4.dp))
                            T(video?.title ?: "", 14f, Color.White, mod = Modifier.weight(1f))
                        }
                        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Glyph(G.PREV, 40.dp, cMain) { exo.seekToPrevious(); touch++ }
                            T("-${Store.seekStep}s", 12f, cMain, mod = Modifier.clickable { seekBy(-step); touch++ }.padding(6.dp))
                            Box(Modifier.size(60.dp).border(2.dp, cMain, CircleShape), Alignment.Center) {
                                Glyph(if (playing) G.PAUSE else G.PLAY, 36.dp, cMain) { if (exo.isPlaying) exo.pause() else exo.play(); touch++ }
                            }
                            T("+${Store.seekStep}s", 12f, cMain, mod = Modifier.clickable { seekBy(step); touch++ }.padding(6.dp))
                            Glyph(G.NEXT, 40.dp, cMain) { exo.seekToNext(); touch++ }
                        }
                        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 12.dp)) {
                            Row(Modifier.fillMaxWidth()) {
                                T(fmt(if (drag != null) (drag!! * dur).toLong() else pos), 11f, Color.White)
                                Spacer(Modifier.weight(1f))
                                T(fmt(dur), 11f, Color.White)
                            }
                            val f = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
                            Slider(
                                value = drag ?: f,
                                onValueChange = { drag = it; touch++ },
                                onValueChangeFinished = { drag?.let { exo.seekTo((it * dur).toLong()) }; drag = null },
                                colors = sliderColors()
                            )
                            if (landscape) tools()
                        }
                    }
                }
            }
        }

        if (!full) {
            // portrait: tools + info + queue below the video
            Column(Modifier.fillMaxWidth().weight(1f)) {
                Box(Modifier.fillMaxWidth().background(Color(0xFF050D12)).padding(horizontal = 8.dp)) { tools() }
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    T("// NOW PLAYING", 11f, cSub)
                    T(video?.title ?: "", 17f, Color.White, true, Modifier.padding(top = 4.dp), 2)
                    T(
                        listOfNotNull(video?.res?.takeIf { it.isNotEmpty() }, video?.let { fmtSize(it.size) }, if (dur > 0) fmt(dur) else null).joinToString(" · "),
                        12f, cSub, mod = Modifier.padding(top = 4.dp)
                    )
                    T(video?.folder ?: "", 10f, cSub, lines = 2)
                    T("// UP NEXT  (${queue.size})", 11f, cSub, mod = Modifier.padding(top = 12.dp))
                }
                val listState = rememberLazyListState()
                LaunchedEffect(index) { if (index in queue.indices) listState.animateScrollToItem(index) }
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    itemsIndexed(queue, key = { _, q -> q.id }) { i, q ->
                        VideoRow(q, i == index) { if (i != index) exo.seekTo(i, if (Store.resume) resumeStart(q) else 0L) }
                    }
                }
            }
        }
    }
}

@Composable
private fun sliderColors() = androidx.compose.material3.SliderDefaults.colors(
    thumbColor = cMain, activeTrackColor = cMain, inactiveTrackColor = cDim
)
