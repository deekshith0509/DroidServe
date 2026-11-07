package com.deekshith.droidserve.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * The in-app "Discover" catalog: free IPTV bundles published by iptv-org, grouped into Languages /
 * Countries / Categories tabs. The index lists come from iptv-org's API JSON (countries/categories);
 * languages are a curated, Indian/Telugu-first set. Each [Entry] resolves to a published `.m3u` the
 * TV fetches + parses with [TvPlaylist] — no phone required.
 */
object IptvCatalog {
    private const val API = "https://iptv-org.github.io/api"
    private const val PL = "https://iptv-org.github.io/iptv"

    data class Entry(val label: String, val url: String, val code: String = "")
    data class Data(val languages: List<Entry>, val countries: List<Entry>, val categories: List<Entry>)

    // iptv-org uses ISO-639-3 language codes. Curated + ordered for an Indian/Telugu-first audience.
    private val LANGS = listOf(
        "tel" to "Telugu", "hin" to "Hindi", "eng" to "English", "tam" to "Tamil",
        "kan" to "Kannada", "mal" to "Malayalam", "ben" to "Bengali", "mar" to "Marathi",
        "guj" to "Gujarati", "urd" to "Urdu", "pan" to "Punjabi", "ori" to "Odia",
        "ara" to "Arabic", "spa" to "Spanish", "fra" to "French", "por" to "Portuguese",
        "rus" to "Russian", "zho" to "Chinese", "jpn" to "Japanese", "kor" to "Korean", "deu" to "German",
    )
    private val FEATURED_COUNTRIES = listOf("IN", "US", "GB", "CA", "AE", "AU", "SG")

    suspend fun load(): Data = withContext(Dispatchers.IO) {
        val countries = runCatching { parseCountries(fetch("$API/countries.json")) }.getOrDefault(emptyList())
        val categories = runCatching { parseCategories(fetch("$API/categories.json")) }.getOrDefault(emptyList())
        val languages = LANGS.map { (c, n) -> Entry(n, "$PL/languages/$c.m3u", c) }
        Data(languages, countries, categories)
    }

    suspend fun fetchBundle(url: String): List<StreamLink> = withContext(Dispatchers.IO) {
        TvPlaylist.parse(fetch(url))
    }

    private fun parseCountries(json: String): List<Entry> {
        val arr = JSONArray(json)
        val list = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val code = o.optString("code"); if (code.isEmpty()) continue
            val flag = o.optString("flag", "")
            list.add(Entry("$flag ${o.optString("name")}".trim(), "$PL/countries/${code.lowercase()}.m3u", code))
        }
        // Featured countries first, then the rest A–Z.
        return list.sortedWith(compareBy(
            { FEATURED_COUNTRIES.indexOf(it.code).let { x -> if (x < 0) Int.MAX_VALUE else x } },
            { it.label },
        ))
    }

    private fun parseCategories(json: String): List<Entry> {
        val arr = JSONArray(json)
        val list = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("id"); if (id.isEmpty()) continue
            list.add(Entry(o.optString("name").ifEmpty { id }, "$PL/categories/$id.m3u", id))
        }
        return list.sortedBy { it.label }
    }

    private fun fetch(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 20000
            setRequestProperty("User-Agent", "DroidServe-TV")
        }
        try {
            if (conn.responseCode !in 200..299) throw RuntimeException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }
}
