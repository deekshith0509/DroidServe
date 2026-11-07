package com.deekshith.droidserve.tv

/**
 * Minimal pure .m3u parser for the TV — the counterpart of the phone's PlaylistImport, producing
 * [StreamLink]s the native player consumes directly. Captures group/logo/tvg-id, the per-channel
 * `#EXTVLCOPT` User-Agent/Referer, and the playlist-level `url-tvg` EPG guide. Used by the in-app
 * iptv-org catalog so the TV can load a bundle with no phone connected.
 */
object TvPlaylist {

    private fun attr(line: String, name: String): String? =
        Regex("""$name="([^"]*)"""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.trim()?.ifEmpty { null }

    fun parse(text: String, max: Int = 20_000): List<StreamLink> {
        val out = ArrayList<StreamLink>()
        var title: String? = null; var group: String? = null; var logo: String? = null
        var tvgId: String? = null; var ua: String? = null; var ref: String? = null; var tvgUrl: String? = null
        var n = 0
        for (raw in text.lineSequence()) {
            if (out.size >= max) break
            val line = raw.trim(); if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) ->
                    tvgUrl = attr(line, "url-tvg") ?: attr(line, "x-tvg-url") ?: tvgUrl
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    group = attr(line, "group-title"); logo = attr(line, "tvg-logo"); tvgId = attr(line, "tvg-id")
                    title = line.substringAfterLast(',').trim().ifEmpty { null }
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) ->
                    if (group == null) group = line.substringAfter(':').trim().ifEmpty { null }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val kv = line.substringAfter(':').trim()
                    val v = kv.substringAfter('=', "").trim().ifEmpty { null }
                    when (kv.substringBefore('=').trim().lowercase()) {
                        "http-user-agent" -> ua = v
                        "http-referrer", "http-referer" -> ref = v
                    }
                }
                line.startsWith("#") -> {}
                else -> {
                    out.add(StreamLink("c${n}_${line.hashCode()}", line, title ?: line.take(60),
                        System.currentTimeMillis(), true, group, logo, tvgId, ua, ref, tvgUrl))
                    n++; title = null; group = null; logo = null; tvgId = null; ua = null; ref = null
                }
            }
        }
        return out
    }
}
