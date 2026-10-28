package com.mux.video.player

import android.content.Context
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Thumbs {
    private val cache = object : LruCache<Long, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }

    @Suppress("DEPRECATION")
    suspend fun load(ctx: Context, v: Video): ImageBitmap? {
        cache.get(v.id)?.let { return it.asImageBitmap() }
        val b: Bitmap? = withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(v.uri, Size(320, 180), null)
                else ThumbnailUtils.createVideoThumbnail(v.path, MediaStore.Images.Thumbnails.MINI_KIND)
            } catch (e: Exception) { null }
        }
        if (b != null) cache.put(v.id, b)
        return b?.asImageBitmap()
    }
}

@Composable
fun Thumb(v: Video, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, v.id) { value = Thumbs.load(ctx, v) }
    Box(modifier.background(Color(0xFF06141A))) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}
