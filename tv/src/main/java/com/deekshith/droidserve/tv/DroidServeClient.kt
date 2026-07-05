package com.deekshith.droidserve.tv

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin HTTP client for the DroidServe JSON API. Uses org.json (bundled with Android)
 * and HttpURLConnection so the TV app carries no networking dependencies.
 *
 * All calls are blocking and must run off the main thread (the ViewModel uses Dispatchers.IO).
 */
class DroidServeClient(private val baseUrl: String) {

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

    private fun getJson(url: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 8000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code for $url")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
