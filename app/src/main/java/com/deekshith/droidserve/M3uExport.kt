package com.deekshith.droidserve

/**
 * Pure, framework-free serializer: turn the stored link list back into an extended-M3U playlist.
 *
 * The inverse of [PlaylistImport] — it round-trips the metadata DroidServe captures (`tvg-id`,
 * `tvg-logo`, `group-title`, and the per-channel `#EXTVLCOPT` User-Agent/Referer), so a user can
 * export everything they've imported/saved back out as a portable `.m3u`. JVM-unit-tested.
 */
object M3uExport {

    fun export(links: List<LinkStore.Link>): String = buildString {
        append("#EXTM3U\n")
        for (l in links) {
            append("#EXTINF:-1")
            l.tvgId?.let { append(" tvg-id=\"").append(san(it)).append('"') }
            l.logo?.let { append(" tvg-logo=\"").append(san(it)).append('"') }
            l.group?.let { append(" group-title=\"").append(san(it)).append('"') }
            append(',').append(san(l.label)).append('\n')
            l.userAgent?.let { append("#EXTVLCOPT:http-user-agent=").append(san(it)).append('\n') }
            l.referrer?.let { append("#EXTVLCOPT:http-referrer=").append(san(it)).append('\n') }
            append(san(l.url)).append('\n')
        }
    }

    // Keep each value on its own line and unquoted-safe: the format is line/quote-delimited.
    private fun san(s: String): String = s.replace('\n', ' ').replace('\r', ' ').replace('"', '\'')
}
