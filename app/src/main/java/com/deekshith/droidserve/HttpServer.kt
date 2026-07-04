package com.deekshith.droidserve

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.*
import android.provider.DocumentsContract
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import java.io.*
import java.util.Collections
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

// ============================================================================
// FileEntry — lightweight SAF record (avoids DocumentFile per-file IPC)
// ============================================================================
data class FileEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val uri: Uri,
    val docId: String          // cached — avoids repeated getDocumentId() calls
)

// ============================================================================
// DirectoryCache — TTL-based cache for SAF listings
// Eliminates redundant IPC queries for the same directory within a short window.
// ============================================================================
object DirectoryCache {
    private const val TTL_MS = 3_000L  // 3 seconds — fresh enough, fast enough
    private const val MAX_ENTRIES = 64

    private data class Entry(val entries: List<FileEntry>, val stamp: Long)

    // Bounded map with insertion-order eviction. accessOrder (LRU) must NOT be enabled here:
    // accessOrder=true makes get() mutate the internal linked list, which is unsafe under the
    // shared read lock and corrupts the map under concurrent reads.
    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Entry>) = size > MAX_ENTRIES
    }
    private val lock = java.util.concurrent.locks.ReentrantReadWriteLock()

    fun get(docId: String): List<FileEntry>? {
        val rl = lock.readLock()
        rl.lock()
        try {
            val e = cache[docId] ?: return null
            return if (SystemClock.elapsedRealtime() - e.stamp < TTL_MS) e.entries else null
        } finally { rl.unlock() }
    }

    fun put(docId: String, entries: List<FileEntry>) {
        val wl = lock.writeLock()
        wl.lock()
        try { cache[docId] = Entry(entries, SystemClock.elapsedRealtime()) }
        finally { wl.unlock() }
    }

    fun invalidate(docId: String) {
        val wl = lock.writeLock()
        wl.lock()
        try { cache.remove(docId) }
        finally { wl.unlock() }
    }

    fun clear() {
        val wl = lock.writeLock()
        wl.lock()
        try { cache.clear() }
        finally { wl.unlock() }
    }
}

// ============================================================================
// ServerOptions — user-configurable server behaviour
// ============================================================================
data class ServerOptions(
    val password: String? = null,
    val username: String? = null,      // null/blank = any username accepted
    val showHidden: Boolean = false,   // list/serve dotfiles & __system entries
    val allowZip: Boolean = true,      // expose ?zip=1 folder downloads
    val allowDownload: Boolean = true, // expose forced (?dl=1) file downloads
    val title: String = "DroidServe"   // shown in the web UI header / tab
)

// ============================================================================
// HTTP Server
// ============================================================================
class HttpServer(
    private val context: Context,
    private val rootUri: Uri,
    port: Int,
    private val options: ServerOptions = ServerOptions()
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "DroidServe"
        private val CPU_COUNT    = Runtime.getRuntime().availableProcessors()
        private val CORE_THREADS = maxOf(8, CPU_COUNT * 2)
        private val MAX_THREADS  = maxOf(32, CPU_COUNT * 4)
        private const val QUEUE_CAPACITY = 512
        private const val KEEP_ALIVE_SEC = 30L
        private const val PIPE_BUFFER    = 2_097_152  // 2 MB pipe for ZIP streaming
        private const val COOKIE_NAME    = "ds_session"

        // Inline MIME prefixes — precomputed set for O(1) lookup
        private val INLINE_PREFIXES = arrayOf("image/", "video/", "audio/", "text/")
        private const val INLINE_PDF = "application/pdf"

        // SAF projection — declared once, reused everywhere
        private val SAF_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
    }

    // Configured credential bytes (null = no auth). Compared constant-time against what's presented.
    private val passwordBytes: ByteArray? = options.password?.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
    private val usernameBytes: ByteArray? = options.username?.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)

    // Cached root doc ID — computed once
    private val rootDocId: String = DocumentsContract.getTreeDocumentId(rootUri)

    // Per-server random session token. Handed out via Set-Cookie once Basic auth succeeds so
    // that follow-up requests (crucially browser `download` anchors, which do NOT reliably
    // resend the cached Authorization header) authenticate via the cookie instead. Fixes
    // downloads failing on remote devices even though the listing loads.
    private val sessionToken: String = run {
        val bytes = ByteArray(24)
        java.security.SecureRandom().nextBytes(bytes)
        Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)
    }

    private val threadIndex = AtomicInteger(0)

    // Active ZIP producer threads run OUTSIDE the request pool, so stop() must track and
    // interrupt them explicitly — otherwise a large in-progress archive keeps reading SAF
    // and holding resources after the server is "stopped".
    private val zipThreads: MutableSet<Thread> = Collections.synchronizedSet(mutableSetOf())

    private val executor = ThreadPoolExecutor(
        CORE_THREADS, MAX_THREADS,
        KEEP_ALIVE_SEC, TimeUnit.SECONDS,
        LinkedBlockingQueue(QUEUE_CAPACITY),
        { r ->
            Thread(r, "ds-${threadIndex.incrementAndGet()}").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY + 1
            }
        },
        ThreadPoolExecutor.CallerRunsPolicy()
    ).also {
        it.allowCoreThreadTimeOut(false)
        it.prestartAllCoreThreads()
    }

    init {
        setAsyncRunner(object : AsyncRunner {
            private val running: MutableSet<ClientHandler> =
                Collections.synchronizedSet(mutableSetOf())

            override fun closeAll() {
                val snap = synchronized(running) { running.toList() }
                snap.forEach { try { it.close() } catch (_: Exception) {} }
            }

            override fun closed(clientHandler: ClientHandler) { running.remove(clientHandler) }

            override fun exec(clientHandler: ClientHandler) {
                try {
                    executor.execute {
                        running.add(clientHandler)
                        try { clientHandler.run() }
                        finally { running.remove(clientHandler) }
                    }
                } catch (_: RejectedExecutionException) {
                    Log.w(TAG, "Pool saturated — dropping")
                    try { clientHandler.close() } catch (_: Exception) {}
                }
            }
        })
    }

    override fun stop() {
        super.stop()
        DirectoryCache.clear()
        // Interrupt any in-flight ZIP producers so they stop reading SAF and release the pipe.
        synchronized(zipThreads) { zipThreads.toList() }.forEach { it.interrupt() }
        executor.shutdown()
        try { executor.awaitTermination(3, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
    }

    // -----------------------------------------------------------------------
    // Dispatch
    // -----------------------------------------------------------------------
    override fun serve(session: IHTTPSession): Response {
        val t0 = System.currentTimeMillis()
        val resp = try {
            dispatch(session)
        } catch (e: Exception) {
            Log.e(TAG, "Unhandled error: ${session.uri}", e)
            errJson(Response.Status.INTERNAL_ERROR, "Internal error")
        }
        val ms = System.currentTimeMillis() - t0
        val code = resp.status.requestStatus
        // Surface every request to the user — nothing hidden
        ServerLog.record(session.remoteIpAddress ?: "?", session.method.name, session.uri, code, ms)
        Log.d(TAG, "${session.remoteIpAddress} ${session.method} ${session.uri} → $code (${ms}ms)")
        return resp
    }

    private fun dispatch(session: IHTTPSession): Response {
        val method = session.method

        if (method == Method.OPTIONS)
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "").also {
                cors(it); it.addHeader("Access-Control-Max-Age", "86400")
            }

        if (method != Method.GET && method != Method.HEAD)
            return errJson(Response.Status.METHOD_NOT_ALLOWED, "Method not allowed")

        // Auth — constant-time compare on the configured password (or a valid session cookie)
        if (!checkAuth(session)) return unauthorized()
        // Mark that this request authenticated via Basic (not the cookie) so we can hand out
        // the session cookie in the response — browser download anchors reuse it automatically.
        val needsCookie = authNeeded() && !hasValidCookie(session)

        // NanoHTTPD already percent-decodes session.uri — decoding again double-decodes and
        // corrupts names containing '%' or '+'.
        val raw = session.uri
        // Reject '..' as a path *segment* (substring matching wrongly 403s names like "a..b")
        // and any control char. resolveFast also blocks traversal per-segment as defence in depth.
        if (raw.any { it < ' ' } || raw.split('/').any { it == ".." })
            return errJson(Response.Status.FORBIDDEN, "Forbidden")

        val path   = raw.trimStart('/')
        val params = session.parameters
        val isZip  = options.allowZip && params["zip"]?.firstOrNull() == "1"
        val isDl   = options.allowDownload && params["dl"]?.firstOrNull() == "1"

        // Count only authenticated requests that resolve to a real, servable resource
        // (not OPTIONS / 401 / 403 / 404). Incrementing here also keeps the inactivity
        // auto-stop timer from being reset by probes for missing paths.
        val resp = if (path.isEmpty()) {
            // Root directory — no resolution needed
            // HEAD must never emit a body, so it takes priority over ?zip=1.
            ServerStateHolder.incrementRequests()
            val entries = listFast(rootDocId)
            when {
                method == Method.HEAD -> dirHeadResponse()
                isZip                 -> zipResponse(entries, "download")
                else                  -> htmlResponse(entries, path, "root")
            }
        } else {
            val resolved = resolveFast(path)
                ?: return errJson(Response.Status.NOT_FOUND, "Not found")

            ServerStateHolder.incrementRequests()
            when {
                resolved.isDirectory && method == Method.HEAD -> dirHeadResponse()
                resolved.isDirectory && isZip                 -> zipResponse(listFast(resolved.docId), resolved.name)
                resolved.isDirectory                          -> htmlResponse(listFast(resolved.docId), path, resolved.name)
                else                                          -> fileResponse(resolved, session, isDl)
            }
        }
        // Hand out the session cookie once so download anchors (which may drop the
        // Authorization header) stay authenticated for the life of this server.
        if (needsCookie) resp.addHeader(
            "Set-Cookie", "$COOKIE_NAME=$sessionToken; Path=/; Max-Age=86400; SameSite=Lax; HttpOnly"
        )
        return resp
    }

    // HEAD on a directory: report headers without building the listing body
    private fun dirHeadResponse(): Response =
        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", "").also {
            cors(it); it.addHeader("Cache-Control", "no-store")
        }

    // -----------------------------------------------------------------------
    // SAF listing — cached, single-batch ContentResolver query
    // -----------------------------------------------------------------------
    private fun listFast(parentDocId: String): List<FileEntry> {
        DirectoryCache.get(parentDocId)?.let { return it }  // Cache hit — zero IPC

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, parentDocId)
        val results = ArrayList<FileEntry>(32)
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                childrenUri, SAF_PROJECTION, null, null, null
            )
            cursor?.let { c ->
                // Resolve column indices once — avoids repeated string lookup in the loop
                val idIdx   = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modIdx  = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                if (idIdx < 0 || nameIdx < 0) return@let  // Malformed cursor

                while (c.moveToNext()) {
                    val name = c.getString(nameIdx) ?: continue
                    if (!options.showHidden && FileUtils.isHiddenOrSystem(name)) continue
                    val docId    = c.getString(idIdx) ?: continue
                    // Guard mimeIdx like the other optional columns — getString(-1) would throw
                    // and abort the whole listing if a provider omits the MIME column.
                    val mime     = (if (mimeIdx >= 0) c.getString(mimeIdx) else null) ?: "application/octet-stream"
                    val isDir    = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val size     = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L
                    val modified = if (modIdx  >= 0 && !c.isNull(modIdx))  c.getLong(modIdx)  else 0L
                    val docUri   = DocumentsContract.buildDocumentUriUsingTree(rootUri, docId)
                    results.add(FileEntry(name, isDir, size, modified, docUri, docId))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "listFast($parentDocId): ${e.message}")
        } finally {
            cursor?.close()
        }

        // Dirs first, then alpha — in-place sort avoids extra allocation
        results.sortWith(compareBy({ if (it.isDirectory) 0 else 1 }, { it.name.lowercase() }))
        DirectoryCache.put(parentDocId, results)
        return results
    }

    // Path resolution — uses listFast (cached) at each level, avoids redundant IPC
    private fun resolveFast(relativePath: String): FileEntry? {
        var currentDocId = rootDocId
        var currentEntry: FileEntry? = null

        for (part in relativePath.splitToSequence('/').filter { it.isNotEmpty() && it != "." }) {
            if (part == ".." || (!options.showHidden && FileUtils.isHiddenOrSystem(part))) return null
            currentEntry = listFast(currentDocId).firstOrNull { it.name == part } ?: return null
            currentDocId = currentEntry.docId
        }
        return currentEntry
    }

    // -----------------------------------------------------------------------
    // Response builders
    // -----------------------------------------------------------------------
    private fun htmlResponse(entries: List<FileEntry>, urlPath: String, dirName: String): Response =
        newFixedLengthResponse(
            Response.Status.OK, "text/html; charset=utf-8",
            FileUtils.buildHtml(
                entries, urlPath, dirName,
                options.title, options.allowZip, options.allowDownload, serverFacts(),
                // Only embed the token in links when auth is enforced; external players
                // (VLC/MX) carry it in the URL since they send no cookie/Basic header.
                authToken = if (authNeeded()) sessionToken else null
            )
        ).also { cors(it); it.addHeader("Cache-Control", "no-store") }

    // Live server/network facts surfaced in the web listing footer
    private fun serverFacts(): List<Pair<String, String>> {
        val facts = ArrayList<Pair<String, String>>(6)
        facts.add("Served by" to "${options.title} @ ${ServerStateHolder.ip.value}:${ServerStateHolder.port.value}")
        facts.add("Requests served" to ServerStateHolder.requestCount.value.toString())
        facts.add("Uptime" to Diagnostics.formatUptime(SystemClock.elapsedRealtime() - ServerStateHolder.startedAtElapsed))
        facts.add("Device" to "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        val ips = NetworkUtils.allIpv4().joinToString(", ") { "${it.first}=${it.second}" }
        if (ips.isNotEmpty()) facts.add("Interfaces" to ips)
        return facts
    }

    private fun fileResponse(entry: FileEntry, session: IHTTPSession, forceDownload: Boolean): Response {
        val mime = FileUtils.getMimeType(entry.name)
        val disp = disposition(entry.name, mime, forceDownload)

        // Open the descriptor once. statSize is authoritative; the cached SAF size may be 0
        // (some providers don't report it) or stale — trusting it would truncate the download.
        val pfd = try { context.contentResolver.openFileDescriptor(entry.uri, "r") }
                  catch (_: Exception) { null }
        val size = pfd?.statSize?.takeIf { it >= 0 } ?: entry.size

        if (session.method == Method.HEAD) {
            try { pfd?.close() } catch (_: Exception) {}
            // null body + declared size → NanoHTTPD emits a single correct Content-Length and no body
            // (adding Content-Length manually produced a duplicate header)
            return newFixedLengthResponse(Response.Status.OK, mime, null, size).also {
                it.addHeader("Accept-Ranges", "bytes")
                it.addHeader("Content-Disposition", disp)
                cors(it)
            }
        }

        if (pfd == null) {
            // Fallback — stream via ContentResolver (no descriptor / page-cache path).
            // Honor a single byte-range by skipping to the start and capping the length,
            // so seeking still works when a provider gives us no descriptor.
            val stream = context.contentResolver.openInputStream(entry.uri)
                ?: return errJson(Response.Status.INTERNAL_ERROR, "Cannot open file")
            val range = session.headers["range"]?.takeUnless { it.contains(',') }
            val parsed = if (range != null && size > 0) parseByteRange(range, size) else null
            if (parsed != null) {
                val (start, end) = parsed
                val length = end - start + 1
                var skipped = 0L
                try {
                    while (skipped < start) {
                        val s = stream.skip(start - skipped)
                        if (s <= 0) break
                        skipped += s
                    }
                } catch (_: Exception) {}
                if (skipped < start) {
                    try { stream.close() } catch (_: Exception) {}
                    return errJson(Response.Status.INTERNAL_ERROR, "Cannot seek file")
                }
                return newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT, mime, CappedStream(stream, length), length
                ).also {
                    it.addHeader("Content-Range", "bytes $start-$end/$size")
                    it.addHeader("Content-Disposition", disp)
                    it.addHeader("Accept-Ranges", "bytes")
                    it.addHeader("Cache-Control", "no-store")
                    cors(it)
                }
            }
            return newFixedLengthResponse(Response.Status.OK, mime, stream, size).also {
                it.addHeader("Content-Disposition", disp)
                it.addHeader("Accept-Ranges", "bytes")
                it.addHeader("Cache-Control", "no-store")
                cors(it)
            }
        }

        // Single range only — ignore multi-range ("bytes=a-b,c-d") and serve the whole file (200)
        val range = session.headers["range"]?.takeUnless { it.contains(',') }
        return if (range != null) rangedResponse(pfd, mime, size, range, disp)
               else               fullResponse(pfd, mime, size, disp)
    }

    // openFileDescriptor + FileInputStream hits the OS page cache directly,
    // avoiding the ContentResolver wrapper overhead vs openInputStream. Takes ownership of pfd.
    private fun fullResponse(pfd: ParcelFileDescriptor, mime: String, size: Long, disp: String): Response {
        val stream = FileInputStream(pfd.fileDescriptor)
        return newFixedLengthResponse(Response.Status.OK, mime, AutoCloseStream(stream, pfd), size).also {
            it.addHeader("Content-Disposition", disp)
            it.addHeader("Accept-Ranges", "bytes")
            it.addHeader("Cache-Control", "no-store")
            cors(it)
        }
    }

    // Takes ownership of pfd; closes it on every early-return path.
    private fun rangedResponse(
        pfd: ParcelFileDescriptor, mime: String, size: Long, rangeHeader: String, disp: String
    ): Response {
        val range = parseByteRange(rangeHeader, size)
        if (range == null) {
            try { pfd.close() } catch (_: Exception) {}
            return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "").also {
                it.addHeader("Content-Range", "bytes */$size"); cors(it)
            }
        }

        val (start, end) = range
        val length = end - start + 1

        val fis = FileInputStream(pfd.fileDescriptor)
        try {
            fis.channel.position(start)   // may throw for non-seekable sources
        } catch (_: Exception) {
            try { fis.close() } catch (_: Exception) {}
            try { pfd.close() } catch (_: Exception) {}
            return errJson(Response.Status.INTERNAL_ERROR, "Cannot seek file")
        }

        return newFixedLengthResponse(
            Response.Status.PARTIAL_CONTENT, mime,
            LimitedStream(fis, length, pfd), length
        ).also {
            it.addHeader("Content-Range", "bytes $start-$end/$size")
            it.addHeader("Accept-Ranges", "bytes")
            it.addHeader("Content-Disposition", disp)
            it.addHeader("Cache-Control", "no-store")
            cors(it)
        }
    }

    private fun zipResponse(entries: List<FileEntry>, dirName: String): Response {
        val pipedIn  = PipedInputStream(PIPE_BUFFER)
        val pipedOut = PipedOutputStream(pipedIn)
        // Dedicated thread — NOT the request pool. With CallerRunsPolicy a saturated pool would
        // run the producer on the consumer thread and deadlock on the pipe buffer.
        // Registered in zipThreads so stop() can interrupt it mid-stream.
        val t = Thread({
            try { FileUtils.zipEntries(context, entries, dirName, pipedOut) { listFast(it.docId) } }
            catch (_: Exception) {}
            finally {
                try { pipedOut.close() } catch (_: Exception) {}
                zipThreads.remove(Thread.currentThread())
            }
        }, "ds-zip").apply { isDaemon = true }
        zipThreads.add(t)
        t.start()
        return newChunkedResponse(Response.Status.OK, "application/zip", pipedIn).also {
            it.addHeader("Content-Disposition", contentDisposition("$dirName.zip", inline = false))
            it.addHeader("Cache-Control", "no-store")
            cors(it)
        }
    }

    // -----------------------------------------------------------------------
    // Auth — parse "user:password" properly, constant-time compare (any username allowed)
    // -----------------------------------------------------------------------
    private fun checkAuth(session: IHTTPSession): Boolean {
        // Auth engages if EITHER a username or a password is configured. Previously a
        // username-only config left the server fully open while the UI reported it as
        // protected — a misleading security hole.
        if (!authNeeded()) return true                       // No credentials → open
        if (hasValidCookie(session)) return true             // Prior successful login
        if (hasValidTokenParam(session)) return true         // ?tok= for external players (VLC/MX)
        val h = session.headers["authorization"] ?: return false
        if (!h.regionMatches(0, "Basic ", 0, 6, ignoreCase = true)) return false   // scheme is case-insensitive
        return try {
            val decoded = Base64.decode(h.substring(6).trim(), Base64.DEFAULT)
            val colon = decoded.indexOf(':'.code.toByte())
            if (colon < 0) return false
            val user = decoded.copyOfRange(0, colon)
            // Everything after the first ':' is the password (passwords may contain ':')
            val pass = decoded.copyOfRange(colon + 1, decoded.size)
            val userOk = usernameBytes?.let { constantTimeEquals(user, it) } ?: true  // null = any username
            val passOk = passwordBytes?.let { constantTimeEquals(pass, it) } ?: true  // null = any password
            userOk and passOk   // non-short-circuit keeps timing uniform
        } catch (_: Exception) { false }
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    // True when credentials are configured, so auth must be enforced.
    private fun authNeeded(): Boolean = passwordBytes != null || usernameBytes != null

    // Validate the DroidServe session cookie against this server's random token.
    private fun hasValidCookie(session: IHTTPSession): Boolean {
        val header = session.headers["cookie"] ?: return false
        val expected = "$COOKIE_NAME=$sessionToken"
        return header.split(';').any { it.trim() == expected }
    }

    // Validate a ?tok=<sessionToken> query param. External players (VLC, MX Player) that open
    // a media URL send neither the browser cookie nor the Authorization header, so the token
    // in the URL is the only credential that travels with the link.
    private fun hasValidTokenParam(session: IHTTPSession): Boolean {
        val tok = session.parameters["tok"]?.firstOrNull() ?: return false
        return constantTimeEquals(tok.toByteArray(Charsets.UTF_8), sessionToken.toByteArray(Charsets.UTF_8))
    }

    private fun unauthorized(): Response =
        newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Unauthorized").also {
            it.addHeader("WWW-Authenticate", "Basic realm=\"DroidServe\"")
            cors(it)
        }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------
    private fun disposition(name: String, mime: String, force: Boolean): String {
        val inline = !force && (INLINE_PREFIXES.any { mime.startsWith(it) } || mime == INLINE_PDF)
        return contentDisposition(name, inline)
    }

    // Build a safe Content-Disposition: strip CR/LF/control chars (header-injection guard),
    // emit an ASCII fallback filename and an RFC 5987 filename* for non-ASCII names.
    private fun contentDisposition(name: String, inline: Boolean): String {
        val type  = if (inline) "inline" else "attachment"
        val clean = name.filter { it >= ' ' && it != '"' && it != '\\' }
        val ascii = buildString { for (c in clean) append(if (c.code in 0x20..0x7E) c else '_') }
        val star  = FileUtils.encodeSeg(name)   // RFC 3986 %-encoding is a valid RFC 5987 value
        return "$type; filename=\"$ascii\"; filename*=UTF-8''$star"
    }

    private fun cors(r: Response) {
        r.addHeader("Access-Control-Allow-Origin", "*")
        r.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        r.addHeader("Access-Control-Allow-Headers", "Content-Type, Range, Authorization")
    }

    private fun errJson(status: Response.Status, msg: String): Response =
        newFixedLengthResponse(status, "application/json", """{"error":"$msg"}""").also { cors(it) }

    // -----------------------------------------------------------------------
    // Stream wrappers
    // -----------------------------------------------------------------------

    /** Wraps FileInputStream + PFD so both are closed when NanoHTTPD is done. */
    private class AutoCloseStream(
        private val fis: FileInputStream,
        private val pfd: ParcelFileDescriptor
    ) : InputStream() {
        override fun read(): Int = fis.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = fis.read(b, off, len)
        override fun available(): Int = fis.available()
        override fun close() {
            try { fis.close() } catch (_: Exception) {}
            try { pfd.close() } catch (_: Exception) {}
        }
    }

    /** Byte-limited stream for range responses. */
    private class LimitedStream(
        private val fis: FileInputStream,
        private var remaining: Long,
        private val pfd: ParcelFileDescriptor
    ) : InputStream() {
        // Buffer for bulk reads — avoids overhead of chaining a BufferedInputStream
        private val buf = ByteArray(65_536)

        override fun read(): Int {
            if (remaining <= 0) return -1
            val n = fis.read(buf, 0, 1)
            if (n > 0) remaining--
            return if (n > 0) buf[0].toInt() and 0xFF else -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = fis.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }

        override fun close() {
            try { fis.close() } catch (_: Exception) {}
            try { pfd.close() } catch (_: Exception) {}
        }
    }

    /** Byte-limited wrapper around an arbitrary InputStream (range on the no-descriptor path). */
    private class CappedStream(
        private val src: InputStream,
        private var remaining: Long
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = src.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = src.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }

        override fun close() { try { src.close() } catch (_: Exception) {} }
    }
}

// ============================================================================
// Byte-range parsing — pure, top-level so it is unit-testable without a Context
// ============================================================================
private val RANGE_RE = Regex("""bytes=(\d*)-(\d*)""")

/**
 * Parse a single HTTP byte-range against [size]. Returns an inclusive (start, end) pair,
 * or null when the range is unsatisfiable/malformed (caller responds 416).
 *  - Suffix ranges ("bytes=-N") are clamped to the whole representation when N > size.
 *  - Non-numeric/overflowing values are treated as unsatisfiable rather than throwing.
 */
internal fun parseByteRange(header: String, size: Long): Pair<Long, Long>? {
    val m = RANGE_RE.find(header) ?: return null
    val s = m.groupValues[1]; val e = m.groupValues[2]
    return try {
        val start = when {
            s.isEmpty() && e.isNotEmpty() -> maxOf(0L, size - e.toLong())   // suffix; clamp to file start
            s.isNotEmpty()                -> s.toLong()
            else                          -> return null
        }
        val end = if (e.isEmpty() || s.isEmpty()) size - 1 else minOf(e.toLong(), size - 1)
        if (start < 0 || start > end || end >= size) null else start to end
    } catch (_: NumberFormatException) { null }
}

// ============================================================================
// Foreground Service
// ============================================================================
class ServerForegroundService : Service() {

    companion object {
        private const val TAG             = "DroidServe"
        private const val CHANNEL_ID      = "droidserve_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START        = "ACTION_START"
        const val ACTION_STOP         = "ACTION_STOP"
        const val EXTRA_URI           = "extra_uri"
        const val EXTRA_PORT          = "extra_port"
        const val EXTRA_PASSWORD      = "extra_password"
        const val EXTRA_USERNAME      = "extra_username"
        const val EXTRA_SHOW_HIDDEN   = "extra_show_hidden"
        const val EXTRA_ALLOW_ZIP     = "extra_allow_zip"
        const val EXTRA_ALLOW_DL      = "extra_allow_dl"
        const val EXTRA_TITLE         = "extra_title"
        const val EXTRA_KEEP_AWAKE    = "extra_keep_awake"
        const val EXTRA_AUTO_STOP_MIN = "extra_auto_stop_min"

        const val PREF_FILE        = "droidserve_prefs"
        private const val KEY_URI  = "server_uri"
        const val KEY_PORT         = "server_port"
        const val KEY_PASSWORD     = "server_password"
        const val KEY_USERNAME     = "server_username"
        const val KEY_SHOW_HIDDEN  = "server_show_hidden"
        const val KEY_ALLOW_ZIP    = "server_allow_zip"
        const val KEY_ALLOW_DL     = "server_allow_dl"
        const val KEY_TITLE        = "server_title"
        const val KEY_KEEP_AWAKE   = "server_keep_awake"
        const val KEY_AUTO_STOP    = "server_auto_stop"
    }

    // All access to httpServer is guarded by `lock`; `stopped` guards against a start that
    // finishes after a concurrent stop (which would otherwise leak an untracked running server).
    private val lock = Any()
    @Volatile private var stopped = false
    private var httpServer: HttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var keepAlive: SilentAudioKeepAlive? = null
    private var autoStopJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() { super.onCreate(); createNotificationChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // Stop arrives via startService / notification PendingIntent — no startForegroundService
            // contract to satisfy, so do NOT promote to foreground here (avoids a notification flash).
            ACTION_STOP  -> { stopAll(); return START_NOT_STICKY }
            ACTION_START -> {
                stopped = false
                startForegroundCompat(buildNotif("Starting…", ""))
                val uriStr = intent.getStringExtra(EXTRA_URI)
                if (uriStr == null) { stopSelf(); return START_NOT_STICKY }
                val cfg = Config(
                    uri             = uriStr,
                    port            = intent.getIntExtra(EXTRA_PORT, 8080),
                    keepAwake       = intent.getBooleanExtra(EXTRA_KEEP_AWAKE, true),
                    autoStopMinutes = intent.getIntExtra(EXTRA_AUTO_STOP_MIN, 0),
                    options = ServerOptions(
                        password      = intent.getStringExtra(EXTRA_PASSWORD)?.ifBlank { null },
                        username      = intent.getStringExtra(EXTRA_USERNAME)?.ifBlank { null },
                        showHidden    = intent.getBooleanExtra(EXTRA_SHOW_HIDDEN, false),
                        allowZip      = intent.getBooleanExtra(EXTRA_ALLOW_ZIP, true),
                        allowDownload = intent.getBooleanExtra(EXTRA_ALLOW_DL, true),
                        title         = intent.getStringExtra(EXTRA_TITLE)?.ifBlank { null } ?: "DroidServe"
                    )
                )
                saveConfig(cfg)
                launchServer(cfg)
            }
            null -> {
                stopped = false
                startForegroundCompat(buildNotif("Starting…", ""))
                val cfg = loadConfig()
                if (cfg.uri == null) { stopSelf(); return START_NOT_STICKY }
                launchServer(cfg)
            }
        }
        return START_STICKY
    }

    private fun launchServer(cfg: Config) {
        val uri = Uri.parse(cfg.uri)
        serviceScope.launch {
            try {
                val old = synchronized(lock) { val o = httpServer; httpServer = null; o }
                try { old?.stop() } catch (_: Exception) {}
                DirectoryCache.clear()

                val srv = HttpServer(this@ServerForegroundService, uri, cfg.port, cfg.options)
                srv.start(30_000, false)

                // Only keep the server if we weren't stopped while it was starting up
                val kept = synchronized(lock) {
                    if (stopped) false else { httpServer = srv; true }
                }
                if (!kept) { try { srv.stop() } catch (_: Exception) {}; return@launch }

                if (cfg.keepAwake) acquireLocks()
                startInactivityWatchdog(cfg.autoStopMinutes)
                val ip  = NetworkUtils.getLocalIpAddress()
                val url = "http://$ip:${cfg.port}"
                ServerStateHolder.onStarted(ip, cfg.port)

                withContext(Dispatchers.Main) {
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                        .notify(NOTIFICATION_ID, buildNotif("Running — $ip:${cfg.port}", url))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Start failed", e)
                ServerStateHolder.onStopped()
                stopSelf()
            }
        }
    }

    // Stop the server automatically after [minutes] with no served requests (0 = never).
    private fun startInactivityWatchdog(minutes: Int) {
        autoStopJob?.cancel()
        if (minutes <= 0) return
        val idleMs = minutes * 60_000L
        autoStopJob = serviceScope.launch {
            while (isActive) {
                delay(60_000)
                if (SystemClock.elapsedRealtime() - ServerStateHolder.lastActivityElapsed >= idleMs) {
                    Log.d(TAG, "Auto-stop: idle for $minutes min")
                    withContext(Dispatchers.Main) { stopAll() }
                    break
                }
            }
        }
    }

    private fun stopAll() {
        stopped = true
        serviceScope.coroutineContext.cancelChildren()
        val srv = synchronized(lock) { val s = httpServer; httpServer = null; s }
        try { srv?.stop() } catch (_: Exception) {}
        releaseLocks()
        DirectoryCache.clear()
        ServerStateHolder.onStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopped = true
        val srv = synchronized(lock) { val s = httpServer; httpServer = null; s }
        try { srv?.stop() } catch (_: Exception) {}
        releaseLocks()
        DirectoryCache.clear()
        ServerStateHolder.onStopped()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Keep CPU and Wi-Fi awake during transfers so downloads don't stall when the screen sleeps.
    @Suppress("DEPRECATION")  // WIFI_MODE_FULL_HIGH_PERF still works; no equivalent across minSdk 24
    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DroidServe::server")
                .also { it.setReferenceCounted(false); it.acquire() }
        }
        if (wifiLock == null) {
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DroidServe::wifi")
                .also { it.setReferenceCounted(false); it.acquire() }
        }
        // Aggressive OEM ROMs (Transsion XOS/PowerGenie, MIUI, etc.) freeze a backgrounded
        // process's threads even with a foreground service + wakelock, stalling downloads
        // until the app is reopened. An always-playing silent audio track keeps the process
        // in a "perceptible/playing" state the freezer will not suspend.
        if (keepAlive == null) {
            keepAlive = SilentAudioKeepAlive().also { try { it.start() } catch (_: Exception) {} }
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        try { keepAlive?.stop() } catch (_: Exception) {}
        wakeLock = null; wifiLock = null; keepAlive = null
    }

    private fun startForegroundCompat(n: Notification) {
        when {
            // specialUse has no daily runtime cap (dataSync is limited to 6h/day on Android 14+)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun buildNotif(status: String, url: String): Notification {
        val openPi = if (url.isNotEmpty()) PendingIntent.getActivity(
            this, 0, Intent(Intent.ACTION_VIEW, Uri.parse(url)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ) else null

        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, ServerForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DroidServe")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_stat_droidserve)
            .setLargeIcon(DroidServeIconDrawable.toCircularBitmap(128))
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPi)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "DroidServe Server", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private data class Config(
        val uri: String?,
        val port: Int,
        val options: ServerOptions,
        val keepAwake: Boolean,
        val autoStopMinutes: Int
    )

    private fun saveConfig(cfg: Config) {
        getSharedPreferences(PREF_FILE, MODE_PRIVATE).edit()
            .putString(KEY_URI, cfg.uri)
            .putInt(KEY_PORT, cfg.port)
            .putString(KEY_PASSWORD, cfg.options.password ?: "")
            .putString(KEY_USERNAME, cfg.options.username ?: "")
            .putBoolean(KEY_SHOW_HIDDEN, cfg.options.showHidden)
            .putBoolean(KEY_ALLOW_ZIP, cfg.options.allowZip)
            .putBoolean(KEY_ALLOW_DL, cfg.options.allowDownload)
            .putString(KEY_TITLE, cfg.options.title)
            .putBoolean(KEY_KEEP_AWAKE, cfg.keepAwake)
            .putInt(KEY_AUTO_STOP, cfg.autoStopMinutes)
            .apply()
    }

    private fun loadConfig(): Config {
        val p = getSharedPreferences(PREF_FILE, MODE_PRIVATE)
        return Config(
            uri             = p.getString(KEY_URI, null),
            port            = p.getInt(KEY_PORT, 8080),
            keepAwake       = p.getBoolean(KEY_KEEP_AWAKE, true),
            autoStopMinutes = p.getInt(KEY_AUTO_STOP, 0),
            options = ServerOptions(
                password      = p.getString(KEY_PASSWORD, "")?.ifBlank { null },
                username      = p.getString(KEY_USERNAME, "")?.ifBlank { null },
                showHidden    = p.getBoolean(KEY_SHOW_HIDDEN, false),
                allowZip      = p.getBoolean(KEY_ALLOW_ZIP, true),
                allowDownload = p.getBoolean(KEY_ALLOW_DL, true),
                title         = p.getString(KEY_TITLE, "DroidServe")?.ifBlank { "DroidServe" } ?: "DroidServe"
            )
        )
    }
}

// ============================================================================
// SilentAudioKeepAlive — plays an inaudible looping tone via AudioTrack.
// On aggressive OEM ROMs (Transsion XOS / PowerGenie, MIUI, etc.) a foreground
// service and wakelock are NOT enough: the freezer still suspends the process's
// threads once it is backgrounded, stalling in-flight HTTP transfers until the
// app is reopened. An actively-playing audio track marks the process as
// "playing/perceptible", which the freezer leaves running.
// ============================================================================
private class SilentAudioKeepAlive {
    @Volatile private var track: android.media.AudioTrack? = null

    fun start() {
        if (track != null) return
        val sampleRate = 8_000
        val minBuf = android.media.AudioTrack.getMinBufferSize(
            sampleRate,
            android.media.AudioFormat.CHANNEL_OUT_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(1_024)

        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = android.media.AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
            .build()

        val t = android.media.AudioTrack(
            attrs, format, minBuf,
            android.media.AudioTrack.MODE_STREAM,
            android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        // Fully silent: zero volume + zero-filled PCM. The track still counts as active.
        try { t.setVolume(0f) } catch (_: Exception) {}
        t.play()
        track = t

        Thread({
            val silence = ShortArray(minBuf / 2)   // all zeros = silence
            val cur = track
            while (cur != null && cur.playState == android.media.AudioTrack.PLAYSTATE_PLAYING) {
                val n = cur.write(silence, 0, silence.size)
                if (n < 0) break   // error — bail out
            }
        }, "ds-keepalive").apply { isDaemon = true }.start()
    }

    fun stop() {
        val t = track
        track = null
        try { t?.stop() } catch (_: Exception) {}
        try { t?.release() } catch (_: Exception) {}
    }
}