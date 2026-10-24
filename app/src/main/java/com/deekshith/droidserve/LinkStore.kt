package com.deekshith.droidserve

import java.io.File

/**
 * Persisted, real-time list of external streaming links the phone pushes to the TV.
 *
 * The web UI "Links" tab adds a pasted URL here; the TV long-polls [awaitChange] and shows the
 * list, letting the user pick one to open in an external player (VLC / MX / Kodi / browser).
 * Unlike [CastQueue] (one-shot, transient commands) this is a durable collection: it is written
 * to a small tab-separated file so links survive a server/app restart.
 *
 * Process-wide singleton. The in-memory core is framework-free so it is unit-testable on the JVM;
 * on-disk persistence is enabled by [attach]ing a file.
 */
object LinkStore {

    data class Link(val id: String, val url: String, val label: String, val addedAt: Long)

    private const val CAP = 200
    private val lock = java.lang.Object()
    private val links = ArrayList<Link>()   // newest first
    private var version = 0
    private var counter = 0L
    private var file: File? = null

    /** Point the store at its backing file and load any persisted links. Call once on server start. */
    fun attach(f: File) = synchronized(lock) {
        file = f
        if (links.isEmpty()) load()
    }

    /** Current (version, links newest-first). */
    fun snapshot(): Pair<Int, List<Link>> = synchronized(lock) { version to ArrayList(links) }

    /**
     * Add a pasted URL. Blanks are ignored; an identical URL is de-duplicated (moved to the top).
     * Returns the new snapshot.
     */
    fun add(url: String): Pair<Int, List<Link>> = synchronized(lock) {
        val clean = sanitize(url)
        if (clean.isNotEmpty()) {
            links.removeAll { it.url == clean }
            links.add(0, Link(nextId(), clean, sanitize(labelFor(clean)).ifEmpty { clean.take(60) }, System.currentTimeMillis()))
            while (links.size > CAP) links.removeAt(links.size - 1)
            bump()
        }
        version to ArrayList(links)
    }

    fun remove(id: String): Pair<Int, List<Link>> = synchronized(lock) {
        if (links.removeAll { it.id == id }) bump()
        version to ArrayList(links)
    }

    fun clear(): Pair<Int, List<Link>> = synchronized(lock) {
        if (links.isNotEmpty()) { links.clear(); bump() }
        version to ArrayList(links)
    }

    /**
     * Block up to [timeoutMs] until [version] differs from [since], then return the snapshot.
     * Returns immediately when the caller is already behind. Powers the long-poll for web UI + TV.
     */
    fun awaitChange(since: Int, timeoutMs: Long): Pair<Int, List<Link>> = synchronized(lock) {
        if (version == since) try { lock.wait(timeoutMs) } catch (_: InterruptedException) {}
        version to ArrayList(links)
    }

    private fun bump() {
        version++
        persist()
        lock.notifyAll()
    }

    private fun nextId(): String = "l${System.currentTimeMillis()}_${counter++}"

    // Strip control chars (incl. the tab/newline the line-based persistence uses as delimiters)
    // and surrounding whitespace so a pasted value is safe to store and serialize.
    private fun sanitize(s: String): String = s.trim().filter { it >= ' ' }

    // ── Persistence: one link per line as "addedAt\tid\turl\tlabel" ─────────────────────────────
    private fun persist() {
        val f = file ?: return
        runCatching {
            f.writeText(links.joinToString("\n") { "${it.addedAt}\t${it.id}\t${it.url}\t${it.label}" })
        }
    }

    private fun load() {
        val f = file ?: return
        runCatching {
            if (!f.exists()) return@runCatching
            for (line in f.readLines()) {
                val p = line.split('\t', limit = 4)
                if (p.size == 4) {
                    links.add(Link(p[1], p[2], p[3], p[0].toLongOrNull() ?: 0L))
                    p[1].substringAfterLast('_').toLongOrNull()?.let { if (it >= counter) counter = it + 1 }
                }
            }
        }
    }

    /** Human label for a URL: filename, magnet display-name, or host. Falls back to the URL. */
    internal fun labelFor(url: String): String {
        if (url.startsWith("magnet:", ignoreCase = true)) {
            val dn = Regex("[?&]dn=([^&]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
            return dn?.let { decode(it) }?.takeIf { it.isNotBlank() } ?: "Magnet link"
        }
        val afterScheme = url.substringAfter("://", url).substringBefore('#')
        val host = afterScheme.substringBefore('/').substringBefore('?')
        val path = afterScheme.substringAfter('/', "").substringBefore('?')
        val lastSeg = path.trimEnd('/').substringAfterLast('/')
        return when {
            lastSeg.isNotBlank() -> decode(lastSeg)
            host.isNotBlank()    -> host
            else                 -> url.take(60)
        }
    }

    private fun decode(s: String): String =
        runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
