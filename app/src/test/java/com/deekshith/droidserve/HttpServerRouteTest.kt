package com.deekshith.droidserve

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL

/**
 * Integration regression net for HttpServer's routing layer.
 *
 * Boots a real NanoHTTPD instance on an ephemeral port against a fake SAF tree URI and fires real
 * HTTP calls at it — same as a browser or the TV app would. Written against the *current,
 * unsplit* HttpServer.kt before Phase 1 begins; both phases must leave this suite green and
 * unmodified.
 *
 * SAF-dependent routes (file serving, ZIP, M3U, directory listing) are excluded here — they
 * require a real ContentResolver / Android provider and are covered by the manual pass rule in
 * the spec (§3.3). They become candidates for automation after Phase 1 introduces a SAF
 * abstraction seam.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HttpServerRouteTest {

    private lateinit var server: HttpServer
    private var port = 0

    @Before fun setUp() {
        // A well-formed tree URI; DocumentsContract.getTreeDocumentId() parses it without a
        // ContentResolver call — safe to pass without a real storage provider.
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/testroot")
        server = HttpServer(ctx, uri, 0)
        server.start()
        port = server.listeningPort
        LinkStore.clear()
    }

    @After fun tearDown() {
        server.stop()
        LinkStore.clear()
    }

    // ── OPTIONS → CORS ────────────────────────────────────────────────────────

    @Test fun options_returnsCors() {
        val c = open("/api/links", method = "OPTIONS")
        assertEquals(200, c.responseCode)
        assertNotNull("ACAO header missing", c.getHeaderField("Access-Control-Allow-Origin"))
        assertNotNull("ACAM header missing", c.getHeaderField("Access-Control-Allow-Methods"))
        c.disconnect()
    }

    // ── Method guard (POST → 405) ─────────────────────────────────────────────

    @Test fun post_returns405() {
        val c = open("/api/links", method = "POST")
        assertEquals(405, c.responseCode)
        c.disconnect()
    }

    // ── Open server: api/links ────────────────────────────────────────────────

    @Test fun apiLinks_emptyList_returns200WithLinksArray() {
        val c = open("/api/links")
        assertEquals(200, c.responseCode)
        val body = c.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(body.contains("\"links\""))
        assertTrue(body.contains("\"version\""))
        c.disconnect()
    }

    @Test fun apiLinksAdd_invalidScheme_returns400() {
        // file:// not in LINK_SCHEME → rejected
        val c = open("/api/links/add?url=file%3A%2F%2F%2Fetc%2Fpasswd")
        assertEquals(400, c.responseCode)
        c.disconnect()
    }

    @Test fun apiLinksAdd_validRtspUrl_returns200AndIncludesLink() {
        // rtsp:// matches LINK_SCHEME; StreamResolver returns it immediately (non-http → no
        // outbound connection made), keeping the test fast and network-free.
        val c = open("/api/links/add?url=rtsp%3A%2F%2Ftest.local%2Fstream.mp4")
        assertEquals(200, c.responseCode)
        val body = c.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue("Added URL should appear in response", body.contains("rtsp://test.local/stream.mp4"))
        c.disconnect()
    }

    @Test fun apiLinksClear_returns200WithEmptyList() {
        val c = open("/api/links/clear")
        assertEquals(200, c.responseCode)
        val body = c.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(body.contains("\"links\":[]"))
        c.disconnect()
    }

    @Test fun apiLinksRemove_unknownId_returns200WithUnchangedList() {
        // Removing a non-existent ID is a no-op; still 200, not 404.
        val c = open("/api/links/remove?id=nonexistent_id")
        assertEquals(200, c.responseCode)
        c.disconnect()
    }

    // ── Path traversal guard ──────────────────────────────────────────────────

    @Test fun dotDotSegment_returns403() {
        val c = open("/../etc/passwd")
        assertEquals(403, c.responseCode)
        c.disconnect()
    }

    // ── Password-protected server ─────────────────────────────────────────────

    @Test fun auth_noCredentials_returns401WithChallenge() {
        val auth = startAuthServer()
        try {
            val c = open("/api/links", port = auth.listeningPort)
            assertEquals(401, c.responseCode)
            val challenge = c.getHeaderField("WWW-Authenticate") ?: ""
            assertTrue("WWW-Authenticate must be Basic", challenge.startsWith("Basic"))
            c.disconnect()
        } finally { auth.stop() }
    }

    @Test fun auth_correctBasic_returns200AndSetsCookie() {
        val auth = startAuthServer()
        try {
            val c = open("/api/links", port = auth.listeningPort,
                headers = mapOf("Authorization" to basicHeader("anyuser", "secret")))
            assertEquals(200, c.responseCode)
            val cookie = c.getHeaderField("Set-Cookie") ?: ""
            assertTrue("Set-Cookie must contain ds_session", cookie.contains("ds_session="))
            c.disconnect()
        } finally { auth.stop() }
    }

    @Test fun auth_wrongPassword_returns401() {
        val auth = startAuthServer()
        try {
            val c = open("/api/links", port = auth.listeningPort,
                headers = mapOf("Authorization" to basicHeader("anyuser", "wrong")))
            assertEquals(401, c.responseCode)
            c.disconnect()
        } finally { auth.stop() }
    }

    @Test fun auth_sessionCookieAccepted() {
        val auth = startAuthServer()
        try {
            val p = auth.listeningPort
            val first = open("/api/links", port = p,
                headers = mapOf("Authorization" to basicHeader("anyuser", "secret")))
            assertEquals(200, first.responseCode)
            val setCookie = first.getHeaderField("Set-Cookie") ?: ""
            val cookie = setCookie.substringBefore(";")   // "ds_session=<token>"
            first.disconnect()

            val second = open("/api/links", port = p, headers = mapOf("Cookie" to cookie))
            assertEquals("Cookie from first auth must be accepted", 200, second.responseCode)
            second.disconnect()
        } finally { auth.stop() }
    }

    @Test fun auth_tokenQueryParamAccepted() {
        val auth = startAuthServer()
        try {
            val p = auth.listeningPort
            val first = open("/api/links", port = p,
                headers = mapOf("Authorization" to basicHeader("anyuser", "secret")))
            assertEquals(200, first.responseCode)
            val setCookie = first.getHeaderField("Set-Cookie") ?: ""
            val token = setCookie.substringAfter("ds_session=").substringBefore(";")
            first.disconnect()

            val encoded = java.net.URLEncoder.encode(token, "UTF-8")
            val c = open("/api/links?tok=$encoded", port = p)
            assertEquals("?tok= must authenticate without cookie or header", 200, c.responseCode)
            c.disconnect()
        } finally { auth.stop() }
    }

    // ── api/links/import ─────────────────────────────────────────────────────

    @Test fun importPost_validM3u_returns200WithAddedCount() {
        val m3u = "#EXTM3U\n#EXTINF:-1,Chan A\nrtsp://test/a.ts\n#EXTINF:-1,Chan B\nrtsp://test/b.ts"
        val c = post("/api/links/import", m3u, "text/plain")
        assertEquals(200, c.responseCode)
        val body = c.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(body.contains("\"added\":2"))
        assertTrue(body.contains("\"skipped\":0"))
        c.disconnect()
    }

    @Test fun importPost_invalidSchemeFiltered_countedAsSkipped() {
        val m3u = "#EXTM3U\n#EXTINF:-1,Bad\nfile:///etc/passwd\n#EXTINF:-1,Good\nrtsp://test/good.ts"
        val c = post("/api/links/import", m3u, "text/plain")
        assertEquals(200, c.responseCode)
        val body = c.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue("bad-scheme entry must be skipped", body.contains("\"added\":1"))
        assertTrue(body.contains("\"skipped\":1"))
        c.disconnect()
    }

    @Test fun importPost_overContentLength_returns413() {
        // Use a server with importMaxBytes=10 so a 25-byte body triggers 413 without writing 2 MB.
        val tiny = startServerWithOptions(ServerOptions(importMaxBytes = 10))
        try {
            val c = post("/api/links/import", "more than ten bytes here!!", "text/plain", tiny.listeningPort)
            assertEquals(413, c.responseCode)
            c.disconnect()
        } finally { tiny.stop() }
    }

    @Test fun importPost_emptyBody_returns200WithZeroAdded() {
        val c = post("/api/links/import", "", "text/plain")
        assertEquals(200, c.responseCode)
        val body = c.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(body.contains("\"added\":0"))
        c.disconnect()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun open(
        path: String,
        port: Int = this.port,
        method: String = "GET",
        headers: Map<String, String> = emptyMap()
    ): HttpURLConnection {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        conn.instanceFollowRedirects = false
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        return conn
    }

    private fun post(path: String, body: String, contentType: String, port: Int = this.port): HttpURLConnection {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("Content-Type", contentType)
        val bytes = body.toByteArray(Charsets.UTF_8)
        conn.setRequestProperty("Content-Length", bytes.size.toString())
        conn.outputStream.use { it.write(bytes) }
        return conn
    }

    private fun startServerWithOptions(opts: ServerOptions): HttpServer {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/testroot")
        return HttpServer(ctx, uri, 0, opts).also { it.start() }
    }

    private fun basicHeader(user: String, pass: String): String =
        "Basic " + java.util.Base64.getEncoder()
            .encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))

    private fun startAuthServer(): HttpServer {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/testroot")
        return HttpServer(ctx, uri, 0, ServerOptions(password = "secret")).also { it.start() }
    }
}
