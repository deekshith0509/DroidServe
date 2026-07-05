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
        val suffix = if (path.isEmpty()) "/api/list" else "/api/list/$path"
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

    /** Fetch a (small) text/code file's contents for the in-app text viewer. */
    fun fetchText(url: String, maxBytes: Int = 512 * 1024): String {
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code == 401) throw AuthException()
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            val bytes = conn.inputStream.use { it.readBytes() }
            val slice = if (bytes.size > maxBytes) bytes.copyOf(maxBytes) else bytes
            return String(slice, Charsets.UTF_8)
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
