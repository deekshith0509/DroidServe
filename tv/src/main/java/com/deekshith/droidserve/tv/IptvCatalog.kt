package com.deekshith.droidserve.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * The in-app "Discover" catalog: free IPTV bundles grouped into Featured / Languages / Countries /
 * Categories tabs. Featured + Languages are instant (static, no network); Countries/Categories are
 * fetched from iptv-org's API JSON. The **Featured** tab leads with a built-in, fully-offline
 * curated bundle shipped in the app (`res/raw/hyderabad_telugu.m3u`) — lift-and-shift, zero setup —
 * followed by one-tap picks that fetch iptv-org playlists. Everything parses on the TV with no phone.
 */
object IptvCatalog {
    private const val API = "https://iptv-org.github.io/api"
    private const val PL = "https://iptv-org.github.io/iptv"

    /** [builtin] entries load from `res/raw/<code>.m3u`; the rest fetch [url]. */
    data class Entry(val label: String, val url: String, val code: String = "", val builtin: Boolean = false)
    data class Data(val featured: List<Entry>, val languages: List<Entry>, val countries: List<Entry>, val categories: List<Entry>)

    // iptv-org uses ISO-639-3 language codes. Curated + ordered for an Indian/Telugu-first audience.
    private val LANGS = listOf(
        "tel" to "Telugu", "hin" to "Hindi", "eng" to "English", "tam" to "Tamil",
        "kan" to "Kannada", "mal" to "Malayalam", "ben" to "Bengali", "mar" to "Marathi",
        "guj" to "Gujarati", "urd" to "Urdu", "pan" to "Punjabi", "ori" to "Odia",
        "ara" to "Arabic", "spa" to "Spanish", "fra" to "French", "por" to "Portuguese",
        "rus" to "Russian", "zho" to "Chinese", "jpn" to "Japanese", "kor" to "Korean", "deu" to "German",
    )
    private val FEATURED_COUNTRIES = listOf("IN", "US", "GB", "CA", "AE", "AU", "SG")

    /** Instant, no-network. Leads with the shipped offline bundle, then popular one-tap iptv-org picks. */
    fun featured(): List<Entry> = listOf(
        Entry("⭐ Hyderabad Telugu — built-in (offline)", "builtin:hyderabad_telugu", "hyderabad_telugu", builtin = true),
        Entry("Telugu", "$PL/languages/tel.m3u", "tel"),
        Entry("Hindi", "$PL/languages/hin.m3u", "hin"),
        Entry("English", "$PL/languages/eng.m3u", "eng"),
        Entry("India — all channels", "$PL/countries/in.m3u", "IN"),
        Entry("News", "$PL/categories/news.m3u", "news"),
        Entry("Movies", "$PL/categories/movies.m3u", "movies"),
        Entry("Kids", "$PL/categories/kids.m3u", "kids"),
        Entry("Sports", "$PL/categories/sports.m3u", "sports"),
        Entry("Music", "$PL/categories/music.m3u", "music"),
    )

    fun languages(): List<Entry> = LANGS.map { (c, n) -> Entry(n, "$PL/languages/$c.m3u", c) }

    /** Fetch the Countries + Categories index lists from iptv-org. */
    suspend fun fetchRemote(): Pair<List<Entry>, List<Entry>> = withContext(Dispatchers.IO) {
        val countries = runCatching { parseCountries(fetch("$API/countries.json")) }.getOrDefault(emptyList())
        val categories = runCatching { parseCategories(fetch("$API/categories.json")) }.getOrDefault(emptyList())
        countries to categories
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
