package com.deekshith.droidserve

/**
 * Pure, framework-free .m3u/.m3u8 parser.
 *
 * Parses EXTM3U playlists into a flat list of [Entry] objects. [Entry.group] captures
 * `group-title` for completeness but is intentionally dropped at the LinkStore boundary —
 * it has no corresponding screen yet. Add [LinkStore.Link.group] only when a group UI is scoped.
 *
 * Handles: EXTINF lines with/without group-title, blank lines, comment-only lines,
 * CRLF and LF line endings, titles containing commas, oversized input (parse stops at [max]
 * entries, not at a byte count — callers cap the input stream before calling parse).
 */
object PlaylistImport {

    data class Entry(val url: String, val title: String, val group: String?, val logo: String? = null)

    fun parse(text: String, max: Int = Int.MAX_VALUE): List<Entry> {
        val out = ArrayList<Entry>()
        var title: String? = null
        var group: String? = null
        var logo: String? = null
        for (raw in text.lineSequence()) {
            if (out.size >= max) break
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    group = Regex("""group-title="([^"]*)"""").find(line)?.groupValues?.get(1)
                    logo = Regex("""tvg-logo="([^"]*)"""").find(line)?.groupValues?.get(1)?.trim()?.ifEmpty { null }
                    title = line.substringAfterLast(',').trim().ifEmpty { null }
                }
                line.startsWith("#") -> {}
                else -> {
                    out.add(Entry(line, title ?: line.take(60), group, logo))
                    title = null; group = null; logo = null
                }
            }
        }
        return out
    }
}
