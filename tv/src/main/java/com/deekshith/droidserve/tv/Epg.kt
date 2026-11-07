package com.deekshith.droidserve.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/** Now/next programme titles for one channel, plus the current programme's window for a progress hint. */
data class EpgNowNext(
    val now: String?,
    val next: String?,
    val nowStartMs: Long = 0,
    val nowStopMs: Long = 0,
) {
    val hasData: Boolean get() = now != null || next != null
    /** 0f..1f through the current programme at [atMs], or null when unknown. */
    fun progress(atMs: Long): Float? {
        if (now == null || nowStopMs <= nowStartMs) return null
        return ((atMs - nowStartMs).toFloat() / (nowStopMs - nowStartMs)).coerceIn(0f, 1f)
    }
}

/**
 * Minimal, dependency-free XMLTV EPG. The parser is a pure string scanner (no XmlPullParser, which
 * is an Android stub on the JVM test classpath) so [parse] + [nowNext] are unit-tested on the JVM,
 * matching the rest of the project. A guide is fetched + parsed at most once per URL and cached;
 * every failure degrades to "no data" so the player never depends on EPG being present.
 */
object Epg {

    data class Programme(val channelId: String, val startMs: Long, val stopMs: Long, val title: String)

    private const val MAX_BYTES = 16 * 1024 * 1024   // cap a guide download; XMLTV dumps can be huge
    private const val MAX_PROGRAMMES = 40_000         // bound memory on a low-RAM TV

    // Parsed-and-filtered programmes per guide URL. A null value marks a URL we already failed on,
    // so a broken guide is never refetched while flipping channels.
    private val cache = HashMap<String, List<Programme>?>()
    private val lock = Mutex()

    /** now/next for [channelId] from [guideUrl], restricted to [wantedIds]. Null on any failure. */
    suspend fun nowNextFor(guideUrl: String, channelId: String, wantedIds: Set<String>, atMs: Long = System.currentTimeMillis()): EpgNowNext? {
        val progs = programmes(guideUrl, wantedIds) ?: return null
        val nn = nowNext(progs, channelId, atMs)
        return if (nn.hasData) nn else null
    }

    private suspend fun programmes(guideUrl: String, wantedIds: Set<String>): List<Programme>? {
        lock.withLock { if (cache.containsKey(guideUrl)) return cache[guideUrl] }
        val parsed = withContext(Dispatchers.IO) {
            runCatching {
                val text = fetch(guideUrl)
                parse(text, wantedIds, MAX_PROGRAMMES)
            }.getOrNull()
        }
        lock.withLock { cache[guideUrl] = parsed }
        return parsed
    }

    private fun fetch(guideUrl: String): String {
        val conn = (URL(guideUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 20000
            setRequestProperty("Accept-Encoding", "gzip")
        }
        try {
            if (conn.responseCode !in 200..299) throw RuntimeException("HTTP ${conn.responseCode}")
            val gz = guideUrl.endsWith(".gz", true) || conn.contentEncoding?.contains("gzip", true) == true
            val raw = conn.inputStream
            val input = if (gz) GZIPInputStream(raw) else raw
            val buf = ByteArray(MAX_BYTES)
            var total = 0
            input.use {
                while (total < MAX_BYTES) {
                    val n = it.read(buf, total, MAX_BYTES - total)
                    if (n < 0) break
                    total += n
                }
            }
            return String(buf, 0, total, Charsets.UTF_8)
        } finally { conn.disconnect() }
    }

    private val PROGRAMME = Regex("""<programme\b([^>]*)>(.*?)</programme>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TITLE = Regex("""<title\b[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    private fun attr(s: String, name: String): String? =
        Regex("""\b$name\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1)

    /** Pure XMLTV scan: extract programmes whose channel is in [wanted] (all, if empty), up to [max]. */
    fun parse(xml: String, wanted: Set<String> = emptySet(), max: Int = Int.MAX_VALUE): List<Programme> {
        val out = ArrayList<Programme>()
        for (m in PROGRAMME.findAll(xml)) {
            if (out.size >= max) break
            val attrs = m.groupValues[1]
            val channel = attr(attrs, "channel") ?: continue
            if (wanted.isNotEmpty() && channel !in wanted) continue
            val start = parseTime(attr(attrs, "start")) ?: continue
            val stop = parseTime(attr(attrs, "stop")) ?: (start + 3_600_000)
            val title = TITLE.find(m.groupValues[2])?.groupValues?.get(1)?.let { unescape(it).trim() } ?: continue
            if (title.isNotEmpty()) out.add(Programme(channel, start, stop, title))
        }
        return out
    }

    /** Current + upcoming programme for [channelId] at [atMs]. Pure; unit-tested. */
    fun nowNext(programmes: List<Programme>, channelId: String, atMs: Long): EpgNowNext {
        val progs = programmes.filter { it.channelId == channelId }.sortedBy { it.startMs }
        val now = progs.firstOrNull { atMs >= it.startMs && atMs < it.stopMs }
        val next = if (now != null) progs.firstOrNull { it.startMs >= now.stopMs }
        else progs.firstOrNull { it.startMs > atMs }
        return EpgNowNext(now?.title, next?.title, now?.startMs ?: 0, now?.stopMs ?: 0)
    }

    // XMLTV times are "yyyyMMddHHmmss" with an optional " +0530"/"-0400" offset; no offset → UTC.
    private val TIME = Regex("""(\d{14})\s*([+-]\d{4})?""")
    private fun parseTime(s: String?): Long? {
        val m = s?.let { TIME.find(it) } ?: return null
        val base = m.groupValues[1]; val off = m.groupValues[2]
        return runCatching {
            if (off.isNotEmpty()) SimpleDateFormat("yyyyMMddHHmmssZ", Locale.US).parse(base + off)?.time
            else SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(base)?.time
        }.getOrNull()
    }

    private fun unescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")
}
