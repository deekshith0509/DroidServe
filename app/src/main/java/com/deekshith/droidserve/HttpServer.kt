package com.deekshith.droidserve

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
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

    // LRU via LinkedHashMap
    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Entry>) = size > MAX_ENTRIES
    }
    private val lock = java.util.concurrent.locks.ReentrantReadWriteLock()

    fun get(docId: String): List<FileEntry>? {
        val rl = lock.readLock()
        rl.lock()
        try {
            val e = cache[docId] ?: return null
            return if (System.currentTimeMillis() - e.stamp < TTL_MS) e.entries else null
        } finally { rl.unlock() }
    }

    fun put(docId: String, entries: List<FileEntry>) {
        val wl = lock.writeLock()
        wl.lock()
        try { cache[docId] = Entry(entries, System.currentTimeMillis()) }
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
// HTTP Server
// ============================================================================
class HttpServer(
    private val context: Context,
    private val rootUri: Uri,
    port: Int,
    private val password: String? = null
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "DroidServe"
        private val CPU_COUNT    = Runtime.getRuntime().availableProcessors()
        private val CORE_THREADS = maxOf(8, CPU_COUNT * 2)
        private val MAX_THREADS  = maxOf(32, CPU_COUNT * 4)
        private const val QUEUE_CAPACITY = 512
        private const val KEEP_ALIVE_SEC = 30L
        private const val PIPE_BUFFER    = 2_097_152  // 2 MB pipe for ZIP streaming

        // Pre-compiled range regex — avoids recompilation on every ranged request
        private val RANGE_RE = Regex("""bytes=(\d*)-(\d*)""")

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

    // Pre-encoded password bytes for fast suffix comparison — avoids string allocation on hot path
    private val passBytes: ByteArray? = password?.let { ":$it".toByteArray(Charsets.UTF_8) }

    // Cached root doc ID — computed once
    private val rootDocId: String = DocumentsContract.getTreeDocumentId(rootUri)

    private val threadIndex = AtomicInteger(0)

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
        executor.shutdown()
        try { executor.awaitTermination(3, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
    }

    // -----------------------------------------------------------------------
    // Dispatch
    // -----------------------------------------------------------------------
    override fun serve(session: IHTTPSession): Response {
        val n  = ServerStateHolder.incrementRequests()
        val t0 = System.currentTimeMillis()
        return try {
            dispatch(session).also {
                Log.d(TAG, "#$n ${session.method} ${session.uri} → ${it.status} (${System.currentTimeMillis() - t0}ms)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unhandled error: ${session.uri}", e)
            errJson(Response.Status.INTERNAL_ERROR, "Internal error")
        }
    }

    private fun dispatch(session: IHTTPSession): Response {
        val method = session.method

        if (method == Method.OPTIONS)
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "").also { cors(it) }

        if (method != Method.GET && method != Method.HEAD)
            return errJson(Response.Status.METHOD_NOT_ALLOWED, "Method not allowed")

        // Auth — O(1) string compare on pre-computed token
        if (!checkAuth(session)) return unauthorized()

        // Path decode + sanitize
        val raw = try { java.net.URLDecoder.decode(session.uri, "UTF-8") }
                  catch (_: Exception) { session.uri }
        if (raw.contains("..") || raw.contains('\u0000'))
            return errJson(Response.Status.FORBIDDEN, "Forbidden")

        val path   = raw.trimStart('/')
        val params = session.parameters
        val isZip  = params["zip"]?.firstOrNull() == "1"
        val isDl   = params["dl"]?.firstOrNull()  == "1"
        val html   = session.headers["accept"]?.contains("text/html") != false

        return if (path.isEmpty()) {
            // Root directory — no resolution needed
            val entries = listFast(rootDocId)
            when {
                isZip -> zipResponse(entries, "download")
                html  -> htmlResponse(entries, path, "root")
                else  -> errJson(Response.Status.OK, "ok")
            }
        } else {
            val resolved = resolveFast(path)
                ?: return errJson(Response.Status.NOT_FOUND, "Not found")

            when {
                resolved.isDirectory && isZip -> zipResponse(listFast(resolved.docId), resolved.name)
                resolved.isDirectory          -> if (html) htmlResponse(listFast(resolved.docId), path, resolved.name)
                                                 else errJson(Response.Status.OK, "ok")
                else                          -> fileResponse(resolved, session, isDl)
            }
        }
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
                    if (FileUtils.isHiddenOrSystem(name)) continue
                    val docId    = c.getString(idIdx) ?: continue
                    val mime     = c.getString(mimeIdx) ?: "application/octet-stream"
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
            if (part == ".." || FileUtils.isHiddenOrSystem(part)) return null
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
            FileUtils.buildHtml(entries, urlPath, dirName)
        ).also { cors(it); it.addHeader("Cache-Control", "no-store") }

    private fun fileResponse(entry: FileEntry, session: IHTTPSession, forceDownload: Boolean): Response {
        val mime = FileUtils.getMimeType(entry.name)
        val size = entry.size
        val disp = disposition(entry.name, mime, forceDownload)

        if (session.method == Method.HEAD) {
            return newFixedLengthResponse(Response.Status.OK, mime, "").also {
                it.addHeader("Content-Length", size.toString())
                it.addHeader("Accept-Ranges", "bytes")
                it.addHeader("Content-Disposition", disp)
                cors(it)
            }
        }

        val range = session.headers["range"]
        return if (range != null) rangedResponse(entry, mime, size, range, disp)
               else               fullResponse(entry, mime, size, disp)
    }

    private fun fullResponse(entry: FileEntry, mime: String, size: Long, disp: String): Response {
        // openFileDescriptor + FileInputStream hits the OS page cache directly
        // and avoids the ContentResolver wrapper overhead vs openInputStream
        val pfd = try { context.contentResolver.openFileDescriptor(entry.uri, "r") }
                  catch (_: Exception) { null }

        return if (pfd != null) {
            val stream = FileInputStream(pfd.fileDescriptor)
            newFixedLengthResponse(Response.Status.OK, mime, AutoCloseStream(stream, pfd), size)
        } else {
            // Fallback to openInputStream
            val stream = context.contentResolver.openInputStream(entry.uri)
                ?: return errJson(Response.Status.INTERNAL_ERROR, "Cannot open file")
            newFixedLengthResponse(Response.Status.OK, mime, stream, size)
        }.also {
            it.addHeader("Content-Disposition", disp)
            it.addHeader("Accept-Ranges", "bytes")
            it.addHeader("Cache-Control", "no-store")
            cors(it)
        }
    }

    private fun rangedResponse(
        entry: FileEntry, mime: String, size: Long, rangeHeader: String, disp: String
    ): Response {
        val range = parseRange(rangeHeader, size)
            ?: return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "").also {
                it.addHeader("Content-Range", "bytes */$size"); cors(it)
            }

        val (start, end) = range
        val length = end - start + 1

        val pfd = try { context.contentResolver.openFileDescriptor(entry.uri, "r") }
                  catch (_: Exception) { null }
            ?: return errJson(Response.Status.INTERNAL_ERROR, "Cannot open file")

        val fis = FileInputStream(pfd.fileDescriptor).also { it.channel.position(start) }

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
        executor.execute {
            try { FileUtils.zipEntries(context, entries, dirName, pipedOut) }
            catch (_: IOException) {}
            finally { try { pipedOut.close() } catch (_: Exception) {} }
        }
        return newChunkedResponse(Response.Status.OK, "application/zip", pipedIn).also {
            it.addHeader("Content-Disposition", "attachment; filename=\"${dirName.replace("\"","_")}.zip\"")
            it.addHeader("Cache-Control", "no-store")
            cors(it)
        }
    }

    // -----------------------------------------------------------------------
    // Auth — O(1) token comparison (no decode on hot path)
    // -----------------------------------------------------------------------
    private fun checkAuth(session: IHTTPSession): Boolean {
        if (passBytes == null) return true   // No password configured
        val h = session.headers["authorization"] ?: return false
        if (!h.startsWith("Basic ")) return false
        return try {
            // Decode once — no Regex, no substring allocation beyond the decoded bytes
            val decoded = Base64.decode(h.substring(6), Base64.DEFAULT)
            // Check that decoded ends with ":password" — allows any username
            val pl = passBytes.size
            decoded.size >= pl && decoded.sliceArray(decoded.size - pl until decoded.size)
                .contentEquals(passBytes)
        } catch (_: Exception) { false }
    }

    private fun unauthorized(): Response =
        newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Unauthorized").also {
            it.addHeader("WWW-Authenticate", "Basic realm=\"DroidServe\"")
            cors(it)
        }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------
    private fun parseRange(header: String, size: Long): Pair<Long, Long>? {
        val m = RANGE_RE.find(header) ?: return null
        val s = m.groupValues[1]; val e = m.groupValues[2]
        val start = when {
            s.isEmpty() && e.isNotEmpty() -> size - e.toLong()
            s.isNotEmpty()                -> s.toLong()
            else                          -> return null
        }
        val end = if (e.isEmpty() || s.isEmpty()) size - 1 else minOf(e.toLong(), size - 1)
        if (start < 0 || start > end || end >= size) return null
        return start to end
    }

    private fun disposition(name: String, mime: String, force: Boolean): String {
        val inline = !force && (INLINE_PREFIXES.any { mime.startsWith(it) } || mime == INLINE_PDF)
        return "${if (inline) "inline" else "attachment"}; filename=\"${name.replace("\"","_")}\""
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
}

// ============================================================================
// Foreground Service
// ============================================================================
class ServerForegroundService : Service() {

    companion object {
        private const val TAG             = "DroidServe"
        private const val CHANNEL_ID      = "droidserve_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START   = "ACTION_START"
        const val ACTION_STOP    = "ACTION_STOP"
        const val EXTRA_URI      = "extra_uri"
        const val EXTRA_PORT     = "extra_port"
        const val EXTRA_PASSWORD = "extra_password"

        private const val PREF_FILE    = "droidserve_prefs"
        private const val KEY_URI      = "server_uri"
        private const val KEY_PORT     = "server_port"
        private const val KEY_PASSWORD = "server_password"
    }

    private var httpServer: HttpServer? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() { super.onCreate(); createNotificationChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildNotif("Starting…", ""))

        when (intent?.action) {
            ACTION_STOP  -> { stopAll(); return START_NOT_STICKY }
            ACTION_START -> {
                val port   = intent.getIntExtra(EXTRA_PORT, -1)
                val uriStr = intent.getStringExtra(EXTRA_URI) ?: return START_NOT_STICKY
                val pass   = intent.getStringExtra(EXTRA_PASSWORD)
                saveConfig(uriStr, port, pass)
                launch(Uri.parse(uriStr), port, pass)
            }
            null -> {
                val cfg = loadConfig()
                if (cfg.uri == null) { stopSelf(); return START_NOT_STICKY }
                launch(Uri.parse(cfg.uri), cfg.port, cfg.password)
            }
        }
        return START_STICKY
    }

    private fun launch(uri: Uri, port: Int, pass: String?) {
        serviceScope.launch {
            try {
                httpServer?.stop(); httpServer = null
                DirectoryCache.clear()

                val srv = HttpServer(this@ServerForegroundService, uri, port, pass)
                srv.start(30_000, false)
                httpServer = srv

                val ip  = NetworkUtils.getLocalIpAddress()
                val url = "http://$ip:$port"
                ServerStateHolder.onStarted(ip, port)

                withContext(Dispatchers.Main) {
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                        .notify(NOTIFICATION_ID, buildNotif("Running — $ip:$port", url))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Start failed", e)
                ServerStateHolder.onStopped()
                stopSelf()
            }
        }
    }

    private fun stopAll() {
        serviceScope.coroutineContext.cancelChildren()
        try { httpServer?.stop() } catch (_: Exception) {}
        httpServer = null
        DirectoryCache.clear()
        ServerStateHolder.onStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        try { httpServer?.stop() } catch (_: Exception) {}
        httpServer = null
        DirectoryCache.clear()
        ServerStateHolder.onStopped()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION_ID, n)
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
            .setSmallIcon(IconCompat.createWithBitmap(DroidServeIconDrawable.toBitmap(96)))
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

    private fun saveConfig(uri: String, port: Int, password: String?) =
        getSharedPreferences(PREF_FILE, MODE_PRIVATE).edit()
            .putString(KEY_URI, uri).putInt(KEY_PORT, port)
            .putString(KEY_PASSWORD, password ?: "").apply()

    private data class Config(val uri: String?, val port: Int, val password: String?)

    private fun loadConfig(): Config {
        val p = getSharedPreferences(PREF_FILE, MODE_PRIVATE)
        return Config(
            p.getString(KEY_URI, null),
            p.getInt(KEY_PORT, 8080),
            p.getString(KEY_PASSWORD, "")?.ifBlank { null }
        )
    }
}