package com.deekshith.droidserve.tv

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin HTTP client for the DroidServe JSON API. Uses org.json (bundled with Android)
 * and HttpURLConnection so the TV app carries no networking dependencies.
 *
 * Password-protected servers: pass a non-null [password] (username may be blank — the
 * phone accepts any username). We send HTTP Basic auth on API calls. Media/file URLs
 * returned by the server already carry a `?tok=` query param, so ExoPlayer and the image
 * viewer stream them directly without needing to replay the auth header.
 *
 * All calls block and must run off the main thread.
 */
class DroidServeClient(
    private val baseUrl: String,
    private val username: String = "",
    private val password: String? = null
) {

    private val authHeader: String? = password?.takeIf { it.isNotEmpty() }?.let {
        val raw = "${username.ifBlank { "tv" }}:$it"
        "Basic " + base64(raw.toByteArray(Charsets.UTF_8))
    }

    fun fetchInfo(): ServerInfo {
        val o = getJson("$baseUrl/api/info")
        return ServerInfo(
            name = o.optString("name", "DroidServe"),
            device = o.optString("device", ""),
            auth = o.optBoolean("auth", false),
            tokenQuery = o.optString("tokenQuery", ""),
            apiVersion = o.optInt("apiVersion", 1)
        )
    }

    /** [path] is the server-relative folder path, empty for root. */
    fun listDir(path: String): Listing {
        // Percent-encode each segment: navigation builds paths from raw (decoded) folder names,
        // so a folder like "My Vids" or "a&b" would otherwise produce an invalid/misrouted URL.
        val encoded = path.split('/').filter { it.isNotEmpty() }.joinToString("/") { encodeSeg(it) }
        val suffix = if (encoded.isEmpty()) "/api/list" else "/api/list/$encoded"
        val o = getJson(baseUrl + suffix)
        val arr = o.optJSONArray("entries")
        val list = ArrayList<RemoteEntry>(arr?.length() ?: 0)
        if (arr != null) for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            list.add(
                RemoteEntry(
                    name = e.optString("name"),
                    isDir = e.optBoolean("isDir"),
                    size = e.optLong("size"),
                    modified = e.optLong("modified"),
                    mime = e.optString("mime"),
                    url = baseUrl + e.optString("url")
                )
            )
        }
        return Listing(o.optString("path", path), list)
    }

    /**
     * Long-poll the phone for the next cast command. The server blocks up to ~25s, then
     * returns 204 (no command) so we immediately re-poll. Returns null on 204/timeout.
     */
    fun pollCast(): CastCommand? {
        val conn = open("$baseUrl/api/tv/poll").apply {
            readTimeout = 30_000   // longer than the server's block window
        }
        try {
            val code = conn.responseCode
            if (code == 401) throw AuthException()
            if (code == 204) return null
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            return CastCommand(
                action = o.optString("action", "play"),
                url = o.optString("url"),
                mime = o.optString("mime")
            )
        } finally { conn.disconnect() }
    }

    private fun getJson(url: String): JSONObject {
        val conn = open(url).apply { setRequestProperty("Accept", "application/json") }
        try {
            val code = conn.responseCode
            if (code == 401) throw AuthException()
            if (code !in 200..299) throw RuntimeException("HTTP $code for $url")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 8000
            requestMethod = "GET"
            authHeader?.let { setRequestProperty("Authorization", it) }
        }
}

/** Thrown when the server rejects credentials (HTTP 401). */
class AuthException : RuntimeException("Authentication required")

// RFC 3986 percent-encoding of a single path segment. Self-contained (no android.net.Uri) so it
// behaves identically on the JVM unit-test classpath and matches the phone's FileUtils.encodeSeg.
private val HEXCHARS = "0123456789ABCDEF".toCharArray()
internal fun encodeSeg(s: String): String {
    val bytes = s.toByteArray(Charsets.UTF_8)
    val out = StringBuilder(bytes.size + 8)
    for (b in bytes) {
        val c = b.toInt() and 0xFF
        val unreserved = c in 0x30..0x39 || c in 0x41..0x5A || c in 0x61..0x7A ||
            c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
        if (unreserved) out.append(c.toChar())
        else out.append('%').append(HEXCHARS[c shr 4]).append(HEXCHARS[c and 0x0F])
    }
    return out.toString()
}

// Standard Base64 (RFC 4648). Self-contained so it behaves identically on Android and on
// the JVM unit-test classpath (android.util.Base64 is a no-op stub in local unit tests).
private val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray()
private fun base64(data: ByteArray): String {
    val out = StringBuilder((data.size + 2) / 3 * 4)
    var i = 0
    while (i < data.size) {
        val b0 = data[i].toInt() and 0xFF
        val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
        val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0
        val n = (b0 shl 16) or (b1 shl 8) or b2
        out.append(B64[(n shr 18) and 0x3F])
        out.append(B64[(n shr 12) and 0x3F])
        out.append(if (i + 1 < data.size) B64[(n shr 6) and 0x3F] else '=')
        out.append(if (i + 2 < data.size) B64[n and 0x3F] else '=')
        i += 3
    }
    return out.toString()
}
