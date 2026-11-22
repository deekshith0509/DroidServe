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
                            if (media != null) {
                                val ok = probePlayable(media, url)
                                Outcome.Single(media, extractTitle(body), ok)
                            } else Outcome.Single(url, extractTitle(body), false)
                        }
                    }
                    else -> Outcome.Single(url, null, true)              // unknown type — let a player try
                }
            } finally { conn.disconnect() }
        } catch (_: Exception) { Outcome.Single(url, null, false) }      // probe failed — don't mark unverified HTML playable
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
                    .map { it.copy(url = absolutize(it.url, url), logo = it.logo?.let { lg -> absolutize(lg, url) }) }
                if (entries.size >= 2) Outcome.Collection(entries) else Outcome.Single(url, null, true)
            }
            else -> Outcome.Single(url, null, true)   // HLS stream or single entry → play the URL as-is
        }

    /** Pure: pull the most likely direct media URL out of an HTML page. Null if none found. */
    fun extractMediaUrl(html: String, baseUrl: String): String? {
        // Collect candidates in priority tiers; first non-empty tier wins.
        val candidates = linkedSetOf<String>()
        fun add(raw: String?) {
            val t = raw?.trim().orEmpty()
            if (t.isEmpty()) return
            if (Regex("\\.(js|css)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(t)) return
            candidates.add(absolutize(t, baseUrl))
        }

        // Tier 1: static tags
        val tagPatterns = listOf(
            Regex("""<source\b[^>]*\bsrc=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<video\b[^>]*\bsrc=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<audio\b[^>]*\bsrc=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+(?:property|name)=["'](?:og:video(?::secure_url|:url)?|twitter:player:stream)["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["'](?:og:video(?::secure_url|:url)?|twitter:player:stream)["']""", RegexOption.IGNORE_CASE),
            Regex("""<link[^>]+rel=["']preload["'][^>]+as=["']video["'][^>]+href=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<link[^>]+href=["']([^"']+)["'][^>]+as=["']video["']""", RegexOption.IGNORE_CASE),
            Regex("""<[^>]+type=["']application/vnd\.apple\.mpegurl["'][^>]*src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        )
        for (p in tagPatterns) for (m in p.findAll(html)) add(m.groupValues.getOrNull(1))

        // Tier 2: JSON/JS literals
        if (candidates.isEmpty()) {
            val jsonPatterns = listOf(
                Regex(""""(?:file|src|source|sources|hls|url|manifest|playlist|stream|m3u8|mpd)"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE),
                Regex(""""(?:file|src|source|sources|hls|url|manifest|playlist|stream|m3u8|mpd)"\s*:\s*'([^']+)'""", RegexOption.IGNORE_CASE)
            )
            for (p in jsonPatterns) for (m in p.findAll(html)) add(m.groupValues.getOrNull(1))
            // sources:[{file|src}]
            Regex("""sources\s*:\s*\[\s*\{[^}]*?(?:file|src)\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(html).forEach { add(it.groupValues.getOrNull(1)) }
            // JSON-LD contentUrl
            Regex(""""contentUrl"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
                .findAll(html).forEach { add(it.groupValues.getOrNull(1)) }
        }

        // Tier 3: decode layer (unescape + base64 blobs + percent)
        if (candidates.isEmpty()) {
            val decoded = unescapeHtml(html)
            for (p in tagPatterns + listOf(
                Regex(""""(?:file|src|source|sources|hls|url|manifest|playlist|stream|m3u8|mpd)"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            )) for (m in p.findAll(decoded)) add(m.groupValues.getOrNull(1))
            // base64 blobs
            Regex("""[A-Za-z0-9+/]{24,}={0,2}""").findAll(html).forEach { m ->
                val dec = base64Decode(m.value)
                if (dec != null && dec.contains("http")) {
                    // extract any media url from decoded
                    Regex("""https?://[^"'<>\s]+""").findAll(dec).forEach { add(it.value) }
                }
            }
            // atob("...")
            Regex("""atob\(\s*["']([^"']+)["']\s*\)""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
                val dec = base64Decode(m.groupValues.getOrNull(1).orEmpty())
                if (dec != null) Regex("""https?://[^"'<>\s]+""").findAll(dec).forEach { add(it.value) }
            }
        }

        // Tier 4: manifest sniff (ranked)
        if (candidates.isEmpty()) {
            val sniff = Regex("""https?://[^"'<>\s\\]+\.(?:m3u8|mpd|ts|mp4|m4v|webm|mov|aac|mp3)(?:\?[^"'<>\s\\]*)?""", RegexOption.IGNORE_CASE)
                .findAll(html).map { it.value }.toList()
            // rank m3u8 > mpd > mp4 > other
            val ranked = sniff.sortedBy { u ->
                when {
                    u.contains(".m3u8", true) -> 0
                    u.contains(".mpd", true) -> 1
                    u.contains(".mp4", true) -> 2
                    else -> 3
                }
            }
            ranked.forEach { add(it) }
        }

        if (candidates.isEmpty()) return null
        // pick best by rank
        return candidates.sortedBy { u ->
            when {
                u.contains(".m3u8", true) -> 0
                u.contains(".mpd", true) -> 1
                u.contains(".mp4", true) -> 2
                else -> 3
            }
        }.first()
    }

    private fun unescapeHtml(s: String): String {
        var t = s.replace("\\/", "/")
        // unicode escapes
        t = Regex("""\\u([0-9a-fA-F]{4})""").replace(t) { m ->
            m.groupValues[1].toInt(16).toChar().toString()
        }
        // html entities
        t = t.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
        // percent
        t = try { java.net.URLDecoder.decode(t, "UTF-8") } catch (_: Exception) { t }
        return t
    }

    private fun base64Decode(s: String): String? = try {
        val clean = s.trim()
        val bytes = java.util.Base64.getDecoder().decode(clean)
        String(bytes, Charsets.UTF_8)
    } catch (_: Exception) { null }

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

    private fun probePlayable(candidate: String, pageUrl: String): Boolean = try {
        val conn = open(candidate)
        conn.requestMethod = "GET"
        conn.setRequestProperty("Range", "bytes=0-1023")
        conn.setRequestProperty("Referer", pageUrl)
        val code = conn.responseCode
        val ctype = (conn.contentType ?: "").lowercase()
        conn.disconnect()
        code in 200..206 && (ctype.contains("video") || ctype.contains("audio") || ctype.contains("mpegurl") || ctype.contains("dash") || ctype.contains("octet-stream"))
    } catch (_: Exception) { false }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 6000
            readTimeout = 8000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) DroidServe")
            setRequestProperty("Accept", "text/html,*/*")
        }

}
