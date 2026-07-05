package com.deekshith.droidserve.tv

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import kotlin.concurrent.thread

/**
 * End-to-end integration test of the phone<->TV contract WITHOUT two devices.
 *
 * A tiny real HTTP server (raw ServerSocket) stands in for the DroidServe phone, emitting
 * responses in the exact JSON shape the phone's ApiJson produces, and enforcing HTTP Basic
 * auth + `?tok=` token access on file bytes exactly like the real HttpServer. The real
 * DroidServeClient talks to it over real TCP/HTTP, so this exercises listing, auth,
 * token-carrying media URLs, text fetch, and byte-range streaming.
 */
class ClientIntegrationTest {

    private lateinit var socket: ServerSocket
    @Volatile private var running = true
    @Volatile var requireAuth = false

    private val password = "s3cret"
    private val token = "TESTTOKEN123"
    private val videoBytes = ByteArray(4096) { (it % 251).toByte() }
    private val textBody = "line1\nline2\nhello world"

    private fun base() = "http://127.0.0.1:${socket.localPort}"
    private val tokQ get() = if (requireAuth) "?tok=$token" else ""

    @Before fun setUp() {
        socket = ServerSocket(0)
        thread(isDaemon = true) {
            while (running) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { handle(client) }
            }
        }
    }

    @After fun tearDown() { running = false; try { socket.close() } catch (_: Exception) {} }

    // --- fake server -------------------------------------------------------------------------
    private fun handle(client: Socket) {
        try {
            client.use { c ->
                val reader = BufferedReader(InputStreamReader(c.getInputStream()))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val target = parts[1]
                val path = target.substringBefore('?')
                val query = target.substringAfter('?', "")

                var auth: String? = null
                var range: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val lower = line.lowercase()
                    when {
                        lower.startsWith("authorization:") -> auth = line.substringAfter(':').trim()
                        lower.startsWith("range:") -> range = line.substringAfter(':').trim()
                    }
                }

                if (!authorized(auth, query)) {
                    write(c, 401, "text/plain", "Unauthorized".toByteArray(),
                        extra = "WWW-Authenticate: Basic realm=\"DroidServe\"\r\n")
                    return
                }
                routeTo(c, path, range)
            }
        } catch (_: Exception) {}
    }

    private fun authorized(auth: String?, query: String): Boolean {
        if (!requireAuth) return true
        if (query.contains("tok=$token")) return true
        if (auth == null || !auth.startsWith("Basic ")) return false
        val decoded = String(Base64.getDecoder().decode(auth.removePrefix("Basic ").trim()))
        return decoded.substringAfter(':') == password
    }

    private fun routeTo(c: Socket, path: String, range: String?) {
        when (path) {
            "/api/info" -> writeJson(c, ApiFmt.info("Pixel", socket.localPort, requireAuth, tokQ))
            "/api/list" -> writeJson(c, ApiFmt.list("", listOf(
                ApiFmt.Row("Movies", true, 0, "inode/directory"),
                ApiFmt.Row("clip.mp4", false, videoBytes.size.toLong(), "video/mp4"),
                ApiFmt.Row("notes.txt", false, textBody.length.toLong(), "text/plain")
            ), tokQ))
            "/api/list/Movies" -> writeJson(c, ApiFmt.list("Movies", listOf(
                ApiFmt.Row("a b.mp4", false, 10, "video/mp4")
            ), tokQ))
            "/clip.mp4" -> serveBytes(c, videoBytes, "video/mp4", range)
            "/notes.txt" -> write(c, 200, "text/plain", textBody.toByteArray())
            else -> write(c, 404, "text/plain", "nope".toByteArray())
        }
    }

    private fun serveBytes(c: Socket, data: ByteArray, type: String, range: String?) {
        if (range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=")
            val start = spec.substringBefore('-').toInt()
            val end = spec.substringAfter('-').ifEmpty { (data.size - 1).toString() }.toInt()
            val slice = data.copyOfRange(start, end + 1)
            write(c, 206, type, slice,
                extra = "Content-Range: bytes $start-$end/${data.size}\r\nAccept-Ranges: bytes\r\n")
        } else {
            write(c, 200, type, data)
        }
    }

    private fun writeJson(c: Socket, body: String) =
        write(c, 200, "application/json; charset=utf-8", body.toByteArray())

    private fun write(c: Socket, code: Int, type: String, body: ByteArray, extra: String = "") {
        val reason = when (code) { 200 -> "OK"; 206 -> "Partial Content"; 401 -> "Unauthorized"; 404 -> "Not Found"; else -> "OK" }
        val header = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            extra + "Connection: close\r\n\r\n"
        c.getOutputStream().apply { write(header.toByteArray()); write(body); flush() }
    }

    // ---- tests ------------------------------------------------------------------------------

    @Test fun info_openServer() {
        val info = DroidServeClient(base()).fetchInfo()
        assertEquals("Pixel", info.name)
        assertFalse(info.auth)
    }

    @Test fun list_root_parsesEntriesAndUrls() {
        val entries = DroidServeClient(base()).listDir("").entries
        assertEquals(3, entries.size)
        assertTrue(entries[0].isDir)
        assertEquals("${base()}/Movies", entries[0].url)
        val video = entries.first { it.name == "clip.mp4" }
        assertTrue(video.isPlayable)
        assertEquals("${base()}/clip.mp4", video.url)
        assertTrue(entries.first { it.name == "notes.txt" }.isText)
    }

    @Test fun list_nested_encodesSpaces() {
        assertEquals("${base()}/Movies/a%20b.mp4",
            DroidServeClient(base()).listDir("Movies").entries[0].url)
    }

    @Test fun text_fetchReturnsContents() {
        val client = DroidServeClient(base())
        val txt = client.listDir("").entries.first { it.name == "notes.txt" }
        assertEquals(textBody, client.fetchText(txt.url))
    }

    @Test fun streaming_rangeRequestReturnsSlice() {
        val url = DroidServeClient(base()).listDir("").entries.first { it.name == "clip.mp4" }.url
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.setRequestProperty("Range", "bytes=0-99")
        assertEquals(206, conn.responseCode)
        assertEquals(100, conn.inputStream.readBytes().size)
        assertEquals("bytes 0-99/${videoBytes.size}", conn.getHeaderField("Content-Range"))
        conn.disconnect()
    }

    // ---- auth path --------------------------------------------------------------------------

    @Test fun auth_wrongPasswordThrowsAuthException() {
        requireAuth = true
        try {
            DroidServeClient(base(), "user", "WRONG").listDir("")
            fail("expected AuthException")
        } catch (_: AuthException) {}
    }

    @Test fun auth_correctPasswordListsAndStreams() {
        requireAuth = true
        val client = DroidServeClient(base(), "user", password)
        val info = client.fetchInfo()
        assertTrue(info.auth)
        assertEquals("?tok=$token", info.tokenQuery)

        val video = client.listDir("").entries.first { it.name == "clip.mp4" }
        assertTrue(video.url.endsWith("?tok=$token"))

        // The tokenized URL must authorize with NO Authorization header (external player path).
        val conn = java.net.URL(video.url).openConnection() as java.net.HttpURLConnection
        assertEquals(200, conn.responseCode)
        assertEquals(videoBytes.size, conn.inputStream.readBytes().size)
        conn.disconnect()
    }

    @Test fun auth_noCredentialsIsRejected() {
        requireAuth = true
        try {
            DroidServeClient(base()).fetchInfo()
            fail("expected AuthException")
        } catch (_: AuthException) {}
    }
}

/** Minimal replica of the phone's ApiJson wire format (app's ApiJsonTest pins the real one). */
private object ApiFmt {
    data class Row(val name: String, val isDir: Boolean, val size: Long, val mime: String)

    fun info(name: String, port: Int, auth: Boolean, tokQ: String) =
        """{"name":"$name","ip":"127.0.0.1","port":$port,"device":"d","auth":$auth,"tokenQuery":"$tokQ","apiVersion":1}"""

    fun list(path: String, rows: List<Row>, tokQ: String): String {
        val base = if (path.isEmpty()) "" else "/$path"
        val items = rows.joinToString(",") { r ->
            val url = "$base/${r.name.replace(" ", "%20")}$tokQ"
            """{"name":"${r.name}","isDir":${r.isDir},"size":${r.size},"modified":0,"mime":"${r.mime}","url":"$url"}"""
        }
        return """{"path":"$path","entries":[$items]}"""
    }
}
