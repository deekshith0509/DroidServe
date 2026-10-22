package com.deekshith.droidserve.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Bounded in-memory LRU of decoded thumbnail bitmaps + an off-main fetch. Deliberately uses the
 * same dependency-free HttpURLConnection + BitmapFactory path as the in-app image viewer, so the
 * TV app pulls in no image-loading library. Thumbnails are small (≤480 px WebP), so a fraction of
 * the heap caches a whole grid.
 */
object ThumbLoader {
    private val cache = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024).toInt() / 8).coerceAtLeast(4 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    /** Synchronous cache peek, for painting instantly on recompose without a flash. */
    fun cached(url: String): Bitmap? = cache.get(url)

    /** Fetch + decode (or return the cached bitmap). Null on any failure → caller shows an icon. */
    suspend fun load(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        val bmp = withContext(Dispatchers.IO) {
            runCatching {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000; readTimeout = 10000
                }
                try {
                    if (conn.responseCode !in 200..299) null
                    else conn.inputStream.use { BitmapFactory.decodeStream(it) }
                } finally { conn.disconnect() }
            }.getOrNull()
        }
        if (bmp != null) cache.put(url, bmp)
        return bmp
    }
}
