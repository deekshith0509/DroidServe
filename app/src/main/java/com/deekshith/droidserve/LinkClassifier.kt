package com.deekshith.droidserve

/**
 * Pure classifier for a fetched .m3u/.m3u8 body: is it a single HLS stream (hand the manifest to a
 * player as-is) or a multi-entry collection (an IPTV channel list to expand into separate links)?
 *
 * The two share the same `.m3u8` extension AND the same `*mpegurl` MIME type, so neither tells them
 * apart — only the body does. The discriminator is the HLS-specific `#EXT-X-` tag family: a master
 * or media playlist always carries at least one (`#EXT-X-STREAM-INF`, `#EXT-X-TARGETDURATION`, …)
 * and is ONE logical stream; an IPTV playlist is `#EXTM3U` with many `#EXTINF` channel entries at
 * distinct URLs and no `#EXT-X-` tags. Framework-free so it is JVM-unit-tested, like [PlaylistImport].
 */
object LinkClassifier {

    enum class Kind {
        /** HLS master/media playlist — a single adaptive stream; play the manifest URL directly. */
        HLS_STREAM,
        /** ≥2 independent channel entries — expand each into its own link. */
        COLLECTION,
        /** Not a multi-entry playlist (bare/single stream, or not an M3U) — treat as one link. */
        SINGLE
    }

    /** Sniff: does [body] look like any M3U playlist? Used when the Content-Type is unhelpful. */
    fun looksLikeM3u(body: String): Boolean =
        body.trimStart().startsWith("#EXTM3U", ignoreCase = true)

    fun classify(body: String): Kind {
        var entries = 0
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXT-X-", ignoreCase = true) -> return Kind.HLS_STREAM
                line.startsWith("#") -> {}            // #EXTM3U / #EXTINF / other directives
                else -> entries++                     // a URL line = one channel (or HLS segment)
            }
        }
        return if (entries >= 2) Kind.COLLECTION else Kind.SINGLE
    }
}
