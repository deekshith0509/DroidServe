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

    /** What a pasted link actually is, once fetched. */
    sealed interface Outcome {
        /** A single thing to open: direct stream, HLS manifest, resolved watch-page, or raw link. */
        data class Single(val url: String, val title: String?, val playable: Boolean) : Outcome
        /** A playlist of ≥2 independent entries to expand into separate links. */
        data class Collection(val entries: List<PlaylistImport.Entry>) : Outcome
    }

    private const val MAX_HTML = 512 * 1024
    private const val MAX_M3U = 64 * 1024 * 1024
    private const val MEDIA_EXT = "m3u8|mpd|mkv|mp4|m4v|webm|ts|mov|avi|flv"

    /**
     * Fetch [url] once and decide what it is. HLS manifests and direct media resolve to
     * [Outcome.Single]; an IPTV/channel playlist resolves to [Outcome.Collection] (entries with
     * relative URLs absolutized against the playlist). Fully best-effort: any failure falls back to
     * a passthrough Single of the original URL.
     */
    fun classify(url: String, maxEntries: Int = 500): Outcome {
        // magnet/rtsp/etc. are handled by their own apps — leave untouched, treat as playable.
        if (!url.startsWith("http", ignoreCase = true)) return Outcome.Single(url, null, true)
        return try {
            val conn = open(url)
            try {
                // Decide by Content-Type, NEVER by file extension: a watch-page URL can itself
                // contain ".mkv" in its path, so trusting the extension hands an HTML page to the
                // player (which then bounces straight back out, looking like a crash).
                val ctype = (conn.contentType ?: "").lowercase()
                when {
                    // mpegurl first — "audio/x-mpegurl" would otherwise match the audio/ branch and
                    // skip the body peek that tells an HLS stream from an IPTV channel list.
                    ctype.contains("mpegurl") -> {
                        val body = conn.inputStream.use { readCapped(it, MAX_M3U) }
                        classifyM3u(url, body, maxEntries)
                    }
                    ctype.startsWith("video/") || ctype.startsWith("audio/") || ctype.contains("dash+xml") ->
                        Outcome.Single(url, null, true)                  // already a direct stream
                    ctype.contains("html") || ctype.contains("xml") || ctype.isEmpty() -> {
                        val body = conn.inputStream.use { readCapped(it, MAX_M3U) }
                        // A server that mislabels an .m3u as text/plain or html — sniff the body.
                        if (LinkClassifier.looksLikeM3u(body)) classifyM3u(url, body, maxEntries)
                        else {
                            val media = extractMediaUrl(body, url)
                            // Found a real stream → playable. Otherwise keep the page URL but flag it
                            // non-playable so the TV opens it in a browser (its own JS player)
                            // instead of bouncing a native player.
                            if (media != null) Outcome.Single(media, extractTitle(body), true)
                            else Outcome.Single(url, extractTitle(body), false)
                        }
                    }
                    else -> Outcome.Single(url, null, true)              // unknown type — let a player try
                }
            } finally { conn.disconnect() }
        } catch (_: Exception) { Outcome.Single(url, null, true) }       // probe failed — best-effort passthrough
    }

    /** Back-compat single-link resolver. A collection collapses to its (playable) playlist URL. */
    fun resolve(url: String): Resolved = when (val o = classify(url)) {
        is Outcome.Single     -> Resolved(o.url, o.title, o.playable)
        is Outcome.Collection -> Resolved(url, null, true)
    }

    private fun classifyM3u(url: String, body: String, maxEntries: Int): Outcome =
        when (LinkClassifier.classify(body)) {
            LinkClassifier.Kind.COLLECTION -> {
                val entries = PlaylistImport.parse(body, maxEntries)
                    .filter { isSupportedLink(it.url) }
                    .map { it.copy(url = absolutize(it.url, url)) }
                if (entries.size >= 2) Outcome.Collection(entries) else Outcome.Single(url, null, true)
            }
            else -> Outcome.Single(url, null, true)   // HLS stream or single entry → play the URL as-is
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

}
