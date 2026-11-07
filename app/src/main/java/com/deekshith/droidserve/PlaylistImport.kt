package com.deekshith.droidserve

/**
 * Pure, framework-free .m3u/.m3u8 parser.
 *
 * Parses EXTM3U playlists into a flat list of [Entry] objects. Beyond the URL + title it captures
 * the fields a native IPTV player needs:
 *  - `group-title` / `#EXTGRP` → [Entry.group] (channel category)
 *  - `tvg-logo` → [Entry.logo]
 *  - `tvg-id` → [Entry.tvgId] (key for EPG matching)
 *  - `#EXTVLCOPT:http-user-agent` / `http-referrer` → [Entry.userAgent] / [Entry.referrer]
 *    (many IPTV streams 403 without the exact header the provider expects)
 *  - the `#EXTM3U` header's `url-tvg` / `x-tvg-url` → [Entry.tvgUrl] (playlist-level EPG guide,
 *    copied onto every entry so it survives the flat LinkStore boundary)
 *
 * Handles: EXTINF lines with/without attributes, blank/comment-only lines, CRLF and LF endings,
 * titles containing commas, oversized input (parse stops at [max] entries, not a byte count —
 * callers cap the input stream before calling parse).
 */
object PlaylistImport {

    data class Entry(
        val url: String,
        val title: String,
        val group: String?,
        val logo: String? = null,
        val tvgId: String? = null,
        val userAgent: String? = null,
        val referrer: String? = null,
        val tvgUrl: String? = null,
    )

    private fun attr(line: String, name: String): String? =
        Regex("""$name="([^"]*)"""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.trim()?.ifEmpty { null }

    fun parse(text: String, max: Int = Int.MAX_VALUE): List<Entry> {
        val out = ArrayList<Entry>()
        var title: String? = null
        var group: String? = null
        var logo: String? = null
        var tvgId: String? = null
        var userAgent: String? = null
        var referrer: String? = null
        var tvgUrl: String? = null   // playlist-level (from the #EXTM3U header); persists across entries
        for (raw in text.lineSequence()) {
            if (out.size >= max) break
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    tvgUrl = attr(line, "url-tvg") ?: attr(line, "x-tvg-url") ?: tvgUrl
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    group = attr(line, "group-title")
                    logo = attr(line, "tvg-logo")
                    tvgId = attr(line, "tvg-id")
                    title = line.substringAfterLast(',').trim().ifEmpty { null }
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    if (group == null) group = line.substringAfter(':').trim().ifEmpty { null }
                }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val kv = line.substringAfter(':').trim()
                    val value = kv.substringAfter('=', "").trim().ifEmpty { null }
                    when (kv.substringBefore('=').trim().lowercase()) {
                        "http-user-agent" -> userAgent = value
                        "http-referrer", "http-referer" -> referrer = value
                    }
                }
                line.startsWith("#") -> {}
                else -> {
                    out.add(Entry(line, title ?: line.take(60), group, logo, tvgId, userAgent, referrer, tvgUrl))
                    title = null; group = null; logo = null; tvgId = null; userAgent = null; referrer = null
                    // tvgUrl is playlist-level — intentionally NOT reset between entries.
                }
            }
        }
        return out
    }
}
