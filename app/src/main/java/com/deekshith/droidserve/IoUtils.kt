package com.deekshith.droidserve

import java.io.InputStream

/** Bounded InputStream → UTF-8 String. Reads at most [max] bytes; never throws on truncation. */
internal fun readCapped(input: InputStream, max: Int): String {
    val buf = ByteArray(max)
    var total = 0
    while (total < max) {
        val n = input.read(buf, total, max - total)
        if (n < 0) break
        total += n
    }
    return String(buf, 0, total, Charsets.UTF_8)
}

// Schemes a media player / torrent client / browser can actually open. Shared by the HTTP link
// routes and the auto-expand ingest so both accept exactly the same set.
private val LINK_SCHEME = Regex("^(https?|rtsps?|rtmps?|mms|ftp|magnet):", RegexOption.IGNORE_CASE)

/** True if [url] uses a scheme we accept as a streaming link. */
internal fun isSupportedLink(url: String): Boolean = url.isNotEmpty() && LINK_SCHEME.containsMatchIn(url)
