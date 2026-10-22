package com.deekshith.droidserve

import java.io.File
import java.security.MessageDigest

/**
 * Disk-backed, bounded LRU cache of pre-rendered WebP thumbnails. Pure JVM (File + crypto only)
 * so the key / ETag / prune logic is unit-testable without an Android Context.
 *
 * A thumbnail is keyed by (documentId, lastModified, size, width). If the underlying file
 * changes, its mtime/size change, so the key changes and a stale thumbnail is never served — the
 * superseded file is reclaimed by [prune]. The cache survives process restarts (that is the whole
 * point of the speed-up); it is only ever size-pruned, never cleared on stop.
 */
class ThumbCache(private val dir: File, private val maxBytes: Long = 64L * 1024 * 1024) {

    init { runCatching { dir.mkdirs() } }

    fun file(key: String): File = File(dir, "$key.webp")

    /** Cache hit → the file (bumping its mtime so it counts as recently used); null on miss. */
    fun get(key: String): File? {
        val f = file(key)
        if (!f.isFile || f.length() == 0L) return null
        runCatching { f.setLastModified(System.currentTimeMillis()) }
        return f
    }

    fun put(key: String, bytes: ByteArray): File {
        val f = file(key)
        runCatching { f.writeBytes(bytes) }
        return f
    }

    /**
     * Evict least-recently-used thumbnails until the directory is under [maxBytes]. Uses a 90%
     * low-water mark so pruning is not re-triggered on every subsequent put.
     */
    fun prune() {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        val target = (maxBytes * 9) / 10
        for (f in files.sortedBy { it.lastModified() }) {   // oldest first
            if (total <= target) break
            val len = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) total -= len
        }
    }

    companion object {
        /** Stable cache key for a file + width. Changes whenever the file's mtime/size changes. */
        fun key(docId: String, lastModified: Long, size: Long, w: Int): String =
            sha1("$docId:$lastModified:$size") + "_" + w

        /** Strong ETag derived from the key (quoted per RFC 7232). */
        fun etag(key: String): String = "\"$key\""

        private val HEX = "0123456789abcdef".toCharArray()
        private fun sha1(s: String): String {
            val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(d.size * 2)
            for (b in d) { val v = b.toInt() and 0xFF; sb.append(HEX[v shr 4]).append(HEX[v and 0x0F]) }
            return sb.toString()
        }
    }
}
