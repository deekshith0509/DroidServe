package com.deekshith.droidserve

import java.net.HttpURLConnection
import java.net.URL

/**
 * Best-effort "what should actually play" resolver for pasted links.
 *
 * Many streaming/movie links are a *watch page* (HTML with an embedded player), not a direct media
 * URL — handing that to VLC/MX opens an HTML page they can't play, so the player immediately exits
 * (looks like a crash). This fetches such a page once and extracts the real media URL (the player's
 * <source>/<video>, an og:video / HLS / MP4 reference, or an embed-config "file"/"src"), so the TV
 * gets something a native player can stream directly. Already-direct URLs are returned untouched.
 *
 * Generic (no per-site rules) and fully best-effort: any failure falls back to the original URL.
 * The HTML-scraping core [extractMediaUrl] is pure so it is unit-testable on the JVM.
 */
object StreamResolver {

    data class Resolved(val url: String, val title: String?, val playable: Boolean)

    private const val MAX_HTML = 512 * 1024
    private const val MEDIA_EXT = "m3u8|mpd|mkv|mp4|m4v|webm|ts|mov|avi|flv"

    fun resolve(url: String): Resolved {
        // magnet/rtsp/etc. are handled by their own apps — leave untouched, treat as playable.
        if (!url.startsWith("http", ignoreCase = true)) return Resolved(url, null, true)
        return try {
            val conn = open(url)
            try {
                // Decide by Content-Type, NEVER by file extension: a watch-page URL can itself
                // contain ".mkv" in its path, so trusting the extension hands an HTML page to the
                // player (which then bounces straight back out, looking like a crash).
                val ctype = (conn.contentType ?: "").lowercase()
                when {
                    ctype.startsWith("video/") || ctype.startsWith("audio/") ||
                        ctype.contains("mpegurl") || ctype.contains("dash+xml") ->
                        Resolved(url, null, true)                        // already a direct stream
                    ctype.contains("html") || ctype.contains("xml") || ctype.isEmpty() -> {
                        val html = conn.inputStream.use { readCapped(it, MAX_HTML) }
                        val media = extractMediaUrl(html, url)
                        // Found a real stream → playable. Otherwise keep the page URL but flag it
                        // non-playable so the TV opens it in a browser (its own JS player) instead
                        // of bouncing a native player.
                        if (media != null) Resolved(media, extractTitle(html), true)
                        else Resolved(url, extractTitle(html), false)
                    }
                    else -> Resolved(url, null, true)                    // unknown type — let a player try
                }
            } finally { conn.disconnect() }
        } catch (_: Exception) { Resolved(url, null, true) }             // probe failed — best-effort passthrough
    }

    /** Pure: pull the most likely direct media URL out of an HTML page. Null if none found. */
    fun extractMediaUrl(html: String, baseUrl: String): String? {
        val patterns = listOf(
            Regex("""<source\b[^>]*\bsrc=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<video\b[^>]*\bsrc=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+(?:property|name)=["'](?:og:video(?::secure_url|:url)?|twitter:player:stream)["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""["']?(?:file|source|src|videoUrl|hls)["']?\s*:\s*["'](https?[^"']+?\.(?:$MEDIA_EXT)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""(https?://[^"'<>\s\\]+\.(?:$MEDIA_EXT)(?:\?[^"'<>\s\\]*)?)""", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            val raw = p.find(html)?.groupValues?.getOrNull(1)?.trim()
            if (!raw.isNullOrEmpty() && !Regex("\\.(js|css)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(raw))
                return absolutize(raw, baseUrl)
        }
        return null
    }

    private fun extractTitle(html: String): String? {
        val t = Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.trim() ?: return null
        return t.removePrefix("Watch ").trim().ifEmpty { null }
    }

    private fun absolutize(raw: String, baseUrl: String): String = try {
        when {
            raw.startsWith("http", ignoreCase = true) -> raw
            raw.startsWith("//")                      -> baseUrl.substringBefore(":") + ":" + raw
            else                                      -> URL(URL(baseUrl), raw).toString()
        }
    } catch (_: Exception) { raw }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 6000
            readTimeout = 8000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) DroidServe")
            setRequestProperty("Accept", "text/html,*/*")
        }

    private fun readCapped(input: java.io.InputStream, max: Int): String {
        val buf = ByteArray(max)
        var total = 0
        while (total < max) {
            val n = input.read(buf, total, max - total)
            if (n < 0) break
            total += n
        }
        return String(buf, 0, total, Charsets.UTF_8)
    }
}
