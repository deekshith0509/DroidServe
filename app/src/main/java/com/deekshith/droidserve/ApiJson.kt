package com.deekshith.droidserve

/**
 * Pure, framework-free JSON serialization for the native-client API
 * (`/api/info`, `/api/list`). Extracted from HttpServer so the exact wire format the
 * Android TV app parses can be unit-tested on the JVM with no Android Context.
 *
 * Hand-rolled to keep the app dependency-free, matching the rest of the project.
 */
object ApiJson {

    /** Minimal shape needed to serialize a listing row (mirrors FileEntry + resolved mime). */
    data class Row(
        val name: String,
        val isDir: Boolean,
        val size: Long,
        val modified: Long,
        val mime: String
    )

    fun escape(s: String): String {
        val out = StringBuilder(s.length + 8)
        for (c in s) when (c) {
            '"'  -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
        }
        return out.toString()
    }

    fun info(
        name: String,
        ip: String,
        port: Int,
        device: String,
        auth: Boolean,
        tokenQuery: String,
        apiVersion: Int = 1
    ): String = buildString {
        append('{')
        append("\"name\":\"").append(escape(name)).append("\",")
        append("\"ip\":\"").append(escape(ip)).append("\",")
        append("\"port\":").append(port).append(',')
        append("\"device\":\"").append(escape(device)).append("\",")
        append("\"auth\":").append(auth).append(',')
        append("\"tokenQuery\":\"").append(escape(tokenQuery)).append("\",")
        append("\"apiVersion\":").append(apiVersion)
        append('}')
    }

    /**
     * @param urlPath server-relative folder path (empty for root)
     * @param tokQ auth query suffix (e.g. "?tok=..."), or "" when auth is off
     */
    fun list(urlPath: String, rows: List<Row>, tokQ: String): String {
        val base = if (urlPath.isEmpty()) "" else "/" + urlPath.split('/')
            .filter { it.isNotEmpty() }.joinToString("/") { FileUtils.encodeSeg(it) }
        return buildString {
            append("{\"path\":\"").append(escape(urlPath)).append("\",\"entries\":[")
            for ((i, e) in rows.withIndex()) {
                if (i > 0) append(',')
                val href = "$base/${FileUtils.encodeSeg(e.name)}"
                append('{')
                append("\"name\":\"").append(escape(e.name)).append("\",")
                append("\"isDir\":").append(e.isDir).append(',')
                append("\"size\":").append(e.size).append(',')
                append("\"modified\":").append(e.modified).append(',')
                append("\"mime\":\"").append(escape(e.mime)).append("\",")
                append("\"url\":\"").append(escape(href + tokQ)).append('"')
                append('}')
            }
            append("]}")
        }
    }

    /** Serialize a cast command for the TV long-poll response. */
    fun castCommand(action: String, url: String, mime: String): String = buildString {
        append('{')
        append("\"action\":\"").append(escape(action)).append("\",")
        append("\"url\":\"").append(escape(url)).append("\",")
        append("\"mime\":\"").append(escape(mime)).append('"')
        append('}')
    }
}
