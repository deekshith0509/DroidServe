package com.deekshith.droidserve.tv

/**
 * Pure classifier for a fetched .m3u/.m3u8 body (TV port of phone LinkClassifier).
 */
object LinkClassifier {

    enum class Kind {
        HLS_STREAM,
        COLLECTION,
        SINGLE
    }

    fun looksLikeM3u(body: String): Boolean =
        body.trimStart().startsWith("#EXTM3U", ignoreCase = true)

    fun classify(body: String): Kind {
        var entries = 0
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXT-X-", ignoreCase = true) -> return Kind.HLS_STREAM
                line.startsWith("#") -> {}
                else -> entries++
            }
        }
        return if (entries >= 2) Kind.COLLECTION else Kind.SINGLE
    }
}