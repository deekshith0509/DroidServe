package com.deekshith.droidserve

import java.text.Normalizer

// ============================================================================
// UploadLogic — framework-free upload helpers (pure, JVM-unit-tested)
//
// All of the security- and correctness-critical decisions for the write path
// (filename sanitising, collision suffixing, CSRF origin matching) live here
// with zero Android dependencies so they can be exhaustively unit-tested on the
// JVM — same pattern as PlaylistImport / LinkClassifier. The Android-coupled
// SAF streaming lives in HttpServer.
// ============================================================================

/** How the server resolves an upload whose final name already exists in the target folder. */
enum class OverwritePolicy { SUFFIX, OVERWRITE, REJECT;
    companion object {
        fun from(raw: String?): OverwritePolicy = when (raw?.trim()?.lowercase()) {
            "overwrite" -> OVERWRITE
            "reject"    -> REJECT
            else        -> SUFFIX
        }
    }
}

object UploadLogic {

    const val PART_SUFFIX = ".part"
    private const val MAX_NAME_BYTES = 255

    /**
     * Sanitise a client-supplied name into a single, safe path segment, or null if it cannot be
     * made safe. Rejects path separators, traversal, and control characters; NFC-normalises;
     * strips trailing dots/spaces; truncates to 255 UTF-8 bytes preserving the extension.
     *
     * This is the single chokepoint for every name that becomes a real on-disk file, so it errs
     * toward rejection — a caller that gets null answers 400 rather than guessing.
     */
    fun sanitizeFilename(raw: String): String? {
        var s = Normalizer.normalize(raw, Normalizer.Form.NFC)
        if (s.isEmpty()) return null
        // One segment only: no separators, no NUL/control, no DEL.
        if (s.any { it < ' ' || it == '/' || it == '\\' || it == '\u007F' }) return null
        // Windows-hostile trailing dots/spaces also defeat the ".part" sentinel trick.
        s = s.trim().trimEnd('.', ' ').trim()
        if (s.isEmpty() || s == "." || s == "..") return null
        s = truncateToBytes(s, MAX_NAME_BYTES)
        return s.ifEmpty { null }
    }

    /** Truncate [name] to at most [maxBytes] UTF-8 bytes, preserving a trailing extension. */
    private fun truncateToBytes(name: String, maxBytes: Int): String {
        if (name.toByteArray(Charsets.UTF_8).size <= maxBytes) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0) name.substring(dot) else ""
        val stem = if (dot > 0) name.substring(0, dot) else name
        val budget = maxBytes - ext.toByteArray(Charsets.UTF_8).size
        if (budget <= 0) return ext.ifEmpty { name }.let { truncateRaw(name, maxBytes) }
        val out = StringBuilder()
        var used = 0
        for (c in stem) {
            val cb = c.toString().toByteArray(Charsets.UTF_8).size
            if (used + cb > budget) break
            out.append(c); used += cb
        }
        return out.toString() + ext
    }

    private fun truncateRaw(name: String, maxBytes: Int): String {
        val out = StringBuilder(); var used = 0
        for (c in name) {
            val cb = c.toString().toByteArray(Charsets.UTF_8).size
            if (used + cb > maxBytes) break
            out.append(c); used += cb
        }
        return out.toString()
    }

    /**
     * Resolve a name collision against the set of names already present in the target folder,
     * returning a non-colliding name. Default policy auto-suffixes `name (1).ext`, `name (2).ext`,
     * … — silent overwrite is never produced here (that is the caller's explicit OVERWRITE path).
     */
    fun resolveCollision(name: String, existing: Set<String>): String {
        if (name !in existing) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext  = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (true) {
            val cand = "$base ($n)$ext"
            if (cand !in existing) return cand
            n++
        }
    }

    /**
     * True when [origin] (an `Origin` header, or a `Referer` URL) has the same host:port as the
     * request's `Host` header. The CSRF guard for cookie/open-mode writes: a cross-origin page
     * driving the browser cannot forge a matching Origin.
     */
    fun originHostMatches(origin: String?, host: String?): Boolean {
        if (origin.isNullOrBlank() || host.isNullOrBlank()) return false
        val afterScheme = origin.substringAfter("://", origin)
        val originHost = afterScheme.substringBefore('/').trim()
        return originHost.equals(host.trim(), ignoreCase = true)
    }
}
