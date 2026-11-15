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
                    else conn.inputStream.use { decodeBounded(it.readBytes()) }
                } finally { conn.disconnect() }
            }.getOrNull()
        }
        if (bmp != null) cache.put(url, bmp)
        return bmp
    }
}

/**
 * Decode [bytes] downsampled near [TARGET_DIM]px per edge (always < 2× that, so even the ~6000²
 * logo that once decoded to a 144 MB bitmap and blew the Canvas draw limit stays safe). IPTV
 * logos/backdrops are arbitrary remote images; bounds-decode first, then sample.
 *
 * shortcut: buffers the whole compressed image in RAM; fine for logos (≤ a few MB) — switch to a
 * streamed/bounded read if arbitrary large non-image URLs ever reach here.
 */
private const val TARGET_DIM = 1024

private fun decodeBounded(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, TARGET_DIM)
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}

/**
 * Power-of-two downsample so a decoded image lands near [target] px on its longest edge — the result
 * is always < 2×[target], keeping even a huge source well under the Canvas draw limit. Pure +
 * unit-tested; shared by the thumbnail loader and the full-screen image viewer (RemoteImage).
 */
fun sampleSize(w: Int, h: Int, target: Int): Int {
    if (w <= 0 || h <= 0) return 1
    var s = 1
    while (w / (s * 2) >= target || h / (s * 2) >= target) s *= 2
    return s
}
