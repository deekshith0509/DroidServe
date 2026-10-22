package com.deekshith.droidserve

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream

/** Media facts surfaced by /meta (and reused for web badges + the lightbox). */
data class MediaMeta(
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0,
    val title: String = "",
    val album: String = "",
    val artist: String = "",
    val exif: Map<String, String> = emptyMap()
)

/**
 * Renders a downscaled WebP thumbnail for a file. Behind an interface so HttpServer's caching +
 * routing logic is unit-testable with a fake (the real implementation needs an Android device).
 */
interface ThumbGen {
    /** WebP bytes scaled to fit [w] px on the longest edge, or null if unsupported/undecodable. */
    fun generate(uri: Uri, mime: String, w: Int): ByteArray?
    /** Dimensions / duration / basic EXIF for [uri], or null when unavailable. */
    fun meta(uri: Uri, mime: String): MediaMeta?
}

/**
 * Android implementation. Decodes with the OS codecs, always downscaling via [BitmapFactory]'s
 * bounds pass + inSampleSize so a 48 MP photo or 4K frame is never fully decoded into memory, then
 * re-encodes to WebP. Every path is failure-tolerant: a throw returns null → the server answers 204
 * → the client shows the type icon. It never crashes the request thread.
 */
class AndroidThumbGen(private val context: Context) : ThumbGen {

    override fun generate(uri: Uri, mime: String, w: Int): ByteArray? = when {
        mime.startsWith("image/") -> imageThumb(uri, w)
        mime.startsWith("video/") -> videoThumb(uri, w)
        mime.startsWith("audio/") -> audioThumb(uri, w)
        else -> null
    }

    private fun imageThumb(uri: Uri, w: Int): ByteArray? = try {
        // Bounds-only decode first — cheap, no pixels — to pick an inSampleSize.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
        else {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, w)
            }
            val src = context.contentResolver.openInputStream(uri)
                ?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (src == null) null else encode(scaleTo(src, w))
        }
    } catch (_: Throwable) { null }

    private fun videoThumb(uri: Uri, w: Int): ByteArray? {
        val r = MediaMetadataRetriever()
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { r.setDataSource(it.fileDescriptor) }
                ?: return null
            val frame = r.getFrameAtTime(-1) ?: r.getFrameAtTime(0) ?: return null
            encode(scaleTo(frame, w))
        } catch (_: Throwable) { null } finally { runCatching { r.release() } }
    }

    // Embedded cover art (FLAC/MP3/etc.) → WebP, for the music library.
    private fun audioThumb(uri: Uri, w: Int): ByteArray? {
        val r = MediaMetadataRetriever()
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { r.setDataSource(it.fileDescriptor) } ?: return null
            val pic = r.embeddedPicture ?: return null
            val bmp = BitmapFactory.decodeByteArray(pic, 0, pic.size) ?: return null
            encode(scaleTo(bmp, w))
        } catch (_: Throwable) { null } finally { runCatching { r.release() } }
    }

    override fun meta(uri: Uri, mime: String): MediaMeta? = try {
        when {
            mime.startsWith("image/") -> {
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, b) }
                MediaMeta(width = b.outWidth.coerceAtLeast(0), height = b.outHeight.coerceAtLeast(0), exif = imageExif(uri))
            }
            mime.startsWith("video/") -> {
                val r = MediaMetadataRetriever()
                try {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { r.setDataSource(it.fileDescriptor) }
                    MediaMeta(
                        width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                        height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                        durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                    )
                } finally { runCatching { r.release() } }
            }
            mime.startsWith("audio/") -> {
                val r = MediaMetadataRetriever()
                try {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { r.setDataSource(it.fileDescriptor) }
                    MediaMeta(
                        durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
                        title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty(),
                        album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(),
                        artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
                    )
                } finally { runCatching { r.release() } }
            }
            else -> null
        }
    } catch (_: Throwable) { null }

    private fun imageExif(uri: Uri): Map<String, String> = try {
        context.contentResolver.openInputStream(uri)?.use { ins ->
            val e = android.media.ExifInterface(ins)   // framework class (API 24+), no dependency
            buildMap {
                e.getAttribute(android.media.ExifInterface.TAG_DATETIME)?.let { put("DateTime", it) }
                e.getAttribute(android.media.ExifInterface.TAG_MAKE)?.let { put("Make", it) }
                e.getAttribute(android.media.ExifInterface.TAG_MODEL)?.let { put("Model", it) }
            }
        } ?: emptyMap()
    } catch (_: Throwable) { emptyMap() }

    // Largest power-of-two sample that keeps ~2x the target resolution (enough detail post-scale).
    private fun sampleSize(w: Int, h: Int, target: Int): Int {
        var s = 1
        var longest = maxOf(w, h)
        while (longest / 2 >= target * 2) { longest /= 2; s *= 2 }
        return s
    }

    private fun scaleTo(src: Bitmap, w: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= w) return src
        val scale = w.toFloat() / longest
        val nw = (src.width * scale).toInt().coerceAtLeast(1)
        val nh = (src.height * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createScaledBitmap(src, nw, nh, true)
        if (out != src) src.recycle()
        return out
    }

    private fun encode(bmp: Bitmap): ByteArray {
        val baos = ByteArrayOutputStream(32 * 1024)
        @Suppress("DEPRECATION")
        bmp.compress(Bitmap.CompressFormat.WEBP, 80, baos)
        bmp.recycle()
        return baos.toByteArray()
    }
}
