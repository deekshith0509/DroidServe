package com.deekshith.droidserve

import android.content.Context
import android.webkit.MimeTypeMap
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object FileUtils {

    // 256 KB buffer — sweet spot for SAF + kernel page cache alignment
    private const val BUFFER_SIZE = 262_144

    // ----------------------------------------------------------------------
    // MIME — pre-allocated HashMap, O(1) lookup, no string scanning
    // ----------------------------------------------------------------------
    private val MIME_MAP: HashMap<String, String> = hashMapOf(
        "html" to "text/html",        "htm"  to "text/html",
        "css"  to "text/css",
        "js"   to "application/javascript",
        "mjs"  to "application/javascript",
        "json" to "application/json",
        "xml"  to "application/xml",
        "pdf"  to "application/pdf",
        "png"  to "image/png",
        "jpg"  to "image/jpeg",       "jpeg" to "image/jpeg",
        "gif"  to "image/gif",
        "webp" to "image/webp",
        "svg"  to "image/svg+xml",
        "ico"  to "image/x-icon",
        "mp4"  to "video/mp4",
        "mkv"  to "video/x-matroska",
        "avi"  to "video/x-msvideo",
        "webm" to "video/webm",
        "mov"  to "video/quicktime",
        "mp3"  to "audio/mpeg",
        "ogg"  to "audio/ogg",
        "wav"  to "audio/wav",
        "flac" to "audio/flac",
        "aac"  to "audio/aac",
        "m4a"  to "audio/mp4",
        "zip"  to "application/zip",
        "tar"  to "application/x-tar",
        "gz"   to "application/gzip",
        "7z"   to "application/x-7z-compressed",
        "txt"  to "text/plain",
        "md"   to "text/markdown",
        "csv"  to "text/csv",
        "vtt"  to "text/vtt",
        "srt"  to "application/x-subrip",
        "apk"  to "application/vnd.android.package-archive",
        "wasm" to "application/wasm",
        "ttf"  to "font/ttf",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
        "m4v"  to "video/x-m4v",        "3gp"  to "video/3gpp",       "3g2"  to "video/3gpp2",
        "ts"   to "video/mp2t",         "m2ts" to "video/mp2t",       "mts"  to "video/mp2t",
        "mpg"  to "video/mpeg",         "mpeg" to "video/mpeg",       "wmv"  to "video/x-ms-wmv",
        "flv"  to "video/x-flv",        "ogv"  to "video/ogg",        "m3u8" to "application/vnd.apple.mpegurl",
        "heic" to "image/heic",         "heif" to "image/heif",       "bmp"  to "image/bmp",
        "tif"  to "image/tiff",         "tiff" to "image/tiff",
        "opus" to "audio/opus",         "mka"  to "audio/x-matroska", "oga"  to "audio/ogg",
        "weba" to "audio/webm",         "mid"  to "audio/midi",
        "bz2"  to "application/x-bzip2","xz"   to "application/x-xz",  "rar"  to "application/vnd.rar"
    )

    // Text-like extensions that browsers should render inline (not download). Kept broad so
    // source files, configs, logs, etc. open as raw text in the browser.
    private val TEXT_EXTS = hashSetOf(
        "txt","md","markdown","log","csv","tsv","ini","conf","cfg","env","properties",
        "yaml","yml","toml","json","xml","js","mjs","ts","tsx","jsx","css","scss","less",
        "html","htm","kt","kts","java","py","rb","go","rs","c","h","cpp","hpp","cc","cs",
        "sh","bash","zsh","bat","ps1","sql","gradle","dockerfile","gitignore","lock","diff","patch"
    )

    fun getMimeType(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        // Extensionless files (README, Makefile, LICENSE, etc.) are almost always text —
        // serve them as text/plain so they display in-browser instead of downloading.
        if (dot < 0) return "text/plain"
        val ext = fileName.substring(dot + 1).lowercase()
        MIME_MAP[ext]?.let { return it }
        if (ext in TEXT_EXTS) return "text/plain"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "text/plain"   // Unknown extension → treat as text so it renders, not downloads
    }

    fun isHiddenOrSystem(name: String): Boolean =
        name.isNotEmpty() && (name[0] == '.' || name.startsWith("__"))

    // Types we render server-side thumbnails for. Images + video have near-certain hit rates, so
    // only these are advertised in listings — art-less audio / PDF / APK would mass-produce 204s.
    fun isThumbnailable(name: String): Boolean {
        val m = getMimeType(name)
        return m.startsWith("image/") || m.startsWith("video/")
    }

    // Cached-thumbnail URL for a decoded relative path. [tokAmp] is "&tok=…" (or "" when auth off).
    // Each path segment is percent-encoded so names with spaces/&/unicode stay valid in the URL.
    fun thumbUrlFor(relPath: String, w: Int, tokAmp: String): String {
        val enc = relPath.split('/').filter { it.isNotEmpty() }.joinToString("/") { encodeSeg(it) }
        return "/thumb/$enc?w=$w$tokAmp"
    }

    // ----------------------------------------------------------------------
    // ZIP — streams FileEntry list directly (no re-listing, no extra IPC)
    // ----------------------------------------------------------------------
    fun zipEntries(
        context: Context,
        entries: List<FileEntry>,
        rootName: String,
        out: OutputStream,
        children: (FileEntry) -> List<FileEntry>
    ) {
        val buf = ByteArray(BUFFER_SIZE)
        val zos = ZipOutputStream(out.buffered(BUFFER_SIZE))
        try {
            for (e in entries) writeEntry(context, e, rootName, zos, buf, children)
            zos.finish()
            zos.flush()
        } finally {
            try { zos.close() } catch (_: Exception) {}
        }
    }

    private fun writeEntry(
        context: Context, e: FileEntry, path: String,
        zos: ZipOutputStream, buf: ByteArray,
        children: (FileEntry) -> List<FileEntry>
    ) {
        val fullPath = "$path/${e.name}"
        if (e.isDirectory) {
            zos.putNextEntry(ZipEntry("$fullPath/")); zos.closeEntry()
            // Recurse so nested files/folders are actually included in the archive
            for (child in children(e)) writeEntry(context, child, fullPath, zos, buf, children)
        } else {
            zos.putNextEntry(ZipEntry(fullPath).also { it.time = e.lastModified })
            var stream: InputStream? = null
            try {
                stream = context.contentResolver.openInputStream(e.uri)
                stream?.let { inp ->
                    var n: Int
                    while (inp.read(buf).also { n = it } != -1) {
                        // Bail out promptly if the server was stopped mid-archive.
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        zos.write(buf, 0, n)
                    }
                }
            } finally {
                try { stream?.close() } catch (_: Exception) {}
                try { zos.closeEntry() } catch (_: Exception) {}
            }
        }
    }

    // ----------------------------------------------------------------------
    // HTML — template is split into prefix/suffix byte arrays (pre-encoded)
    // to avoid repeated string allocation + .replace() on the big template.
    // Only the dynamic middle (entries list) is built per-request.
    // ----------------------------------------------------------------------

    fun buildHtml(
        entries: List<FileEntry>,
        urlPath: String,
        dirName: String,
        title: String = "DroidServe",
        allowZip: Boolean = true,
        allowDownload: Boolean = true,
        serverFacts: List<Pair<String, String>> = emptyList(),
        authToken: String? = null
    ): String {
        val sb = StringBuilder(4096 + entries.size * 256)
        val safeTitle = escHtml(title.ifBlank { "DroidServe" })
        // Query suffix that carries the auth token so external apps (VLC/MX) can stream
        // without a cookie/Basic header. Empty when auth is off.
        val tokQ = if (authToken != null) "?tok=${encodeSeg(authToken)}" else ""
        val tokAmp = if (authToken != null) "&tok=${encodeSeg(authToken)}" else ""

        sb.append(HTML_HEAD_1)
        sb.append(safeTitle); sb.append(" — "); sb.append(escHtml(dirName))
        sb.append(HTML_HEAD_2A)
        sb.append(safeTitle)
        sb.append(HTML_HEAD_2B)

        // Clickable breadcrumb trail — each segment links to its own directory.
        buildBreadcrumb(sb, urlPath)

        sb.append(HTML_HEAD_3)

        // Toolbar: current-folder ZIP + sort controls
        sb.append("""<div class="tb"><span class="st" id="st"></span>""")
        sb.append("""<button class="sb" id="viewtg" title="Grid / list view" aria-label="Toggle grid or list view">⊞ Grid</button>""")
        if (allowZip) {
            val here = encodePath(urlPath)
            val zipHref = if (here.isEmpty()) "/?zip=1" else "/$here?zip=1"
            sb.append("""<a class="sb zipall" href="""").append(zipHref).append(tokAmp)
            sb.append("""" aria-label="Download this folder as ZIP">⬇ Folder ZIP</a>""")
        }
        sb.append("""<button class="sb on" data-s="d">Default</button>""")
        sb.append("""<button class="sb" data-s="n">Name</button>""")
        sb.append("""<button class="sb" data-s="s">Size ↓</button>""")
        sb.append("""<button class="sb" data-s="t">Date ↓</button>""")
        sb.append("</div>")   // close .tb

        // Parent link — OUTSIDE the sortable list so it stays pinned at the top
        // and is never reordered/hidden by sorting or filtering.
        if (urlPath.isNotEmpty() && urlPath != "/") {
            val parent = encodePath(urlPath.trimEnd('/').substringBeforeLast('/', ""))
            sb.append("""<div class="item dir parent"><a class="item-main" href="/""")
            sb.append(parent)
            sb.append(""""><span class="icon">⬆️</span><div class="info"><div class="name">..</div><div class="meta">Parent folder</div></div></a></div>""")
        }

        sb.append("""<div class="list" id="ls">""")

        val base = encodePath(urlPath)

        // Index sidecar subtitles (.srt/.vtt) by the media stem they belong to. Convention:
        // "movie.srt" or "movie.en.srt" both attach to "movie.mp4". Lets the in-browser player
        // offer subtitle tracks with no extra round-trips (the mapping ships in the page).
        val subsByStem = HashMap<String, MutableList<Pair<String, String>>>()  // stem -> [(label, srtOrVttName)]
        for (e in entries) {
            if (e.isDirectory) continue
            val lower = e.name.lowercase()
            if (!lower.endsWith(".srt") && !lower.endsWith(".vtt")) continue
            val noExt = e.name.substring(0, e.name.lastIndexOf('.'))          // movie.en
            val stem  = if (noExt.contains('.')) noExt.substringBeforeLast('.') else noExt  // movie
            val lang  = if (noExt.contains('.')) noExt.substringAfterLast('.') else ""      // en
            val label = if (lang.isNotEmpty() && lang.length <= 5) lang else "Subtitles"
            subsByStem.getOrPut(stem.lowercase()) { mutableListOf() }.add(label to e.name)
        }

        for (e in entries) {
            val eName = e.name
            val enc   = encodeSeg(eName)
            val href  = if (base.isEmpty()) enc else "$base/$enc"

            if (e.isDirectory) {
                sb.append("""<div class="item dir" data-name="${escHtml(eName)}" data-size="-1">""")
                sb.append("""<a class="item-main" href="/""").append(href).append("""">""")
                sb.append("""<span class="thumb-wrap"><span class="icon">📁</span></span>""")
                sb.append("""<div class="info"><div class="name">""").append(escHtml(eName))
                sb.append("""</div><div class="meta">Directory</div></div></a>""")
                if (allowZip) {
                    val zipHref = "/$href?zip=1$tokAmp"
                    sb.append("""<a class="btn-zip" href="""").append(zipHref).append("""" aria-label="Download folder as ZIP">⬇ ZIP</a>""")
                }
                sb.append("</div>")
            } else {
                val date = formatDate(e.lastModified)
                val meta = if (date.isEmpty()) formatSize(e.size) else "${formatSize(e.size)} · $date"
                val mime = getMimeType(eName)
                val isVideo = mime.startsWith("video/")
                val isAudio = mime.startsWith("audio/")
                // data-play marks streamable media so the in-browser player overlay intercepts
                // the click (v=video, a=audio) and can build a next/prev playlist for the folder.
                val playAttr = when { isVideo -> """ data-play="v"""" ; isAudio -> """ data-play="a"""" ; else -> "" }
                // Attach any sidecar subtitles for this video as a JSON list the player reads.
                // Each track URL uses ?vtt=1 so the server hands back browser-ready WebVTT.
                var subsAttr = ""
                if (isVideo) {
                    val stem = if (eName.contains('.')) eName.substring(0, eName.lastIndexOf('.')) else eName
                    val subs = subsByStem[stem.lowercase()]
                    if (!subs.isNullOrEmpty()) {
                        val json = subs.joinToString(",", "[", "]") { (label, subName) ->
                            val subHref = if (base.isEmpty()) encodeSeg(subName) else "$base/${encodeSeg(subName)}"
                            val subUrl = "/$subHref?vtt=1$tokAmp"
                            """{"label":"${jsonStr(label)}","url":"${jsonStr(subUrl)}"}"""
                        }
                        subsAttr = """ data-subs="${escHtml(json)}""""
                    }
                }
                // Thumbnailable files carry the cached-thumbnail URL so the grid can lazy-load it.
                val thumbAttr = if (isThumbnailable(eName)) {
                    val rel = if (urlPath.isEmpty()) eName else "${urlPath.trimEnd('/')}/$eName"
                    """ data-thumb="${escHtml(thumbUrlFor(rel, 320, tokAmp))}""""
                } else ""
                // Every file carries its mime so client JS can offer "open with" (Android chooser).
                sb.append("""<div class="item file" data-name="${escHtml(eName)}" data-size="${e.size}" data-date="${e.lastModified}" data-mime="${escHtml(mime)}"$playAttr$subsAttr$thumbAttr>""")
                // Tapping the name opens the file directly: the browser plays/shows it inline
                // where it can; on Android, JS below hands it to the OS "open with" chooser
                // (VLC/MX for video, etc.). The ⬇ button is the only download action.
                sb.append("""<a class="item-main" href="/""").append(href).append(tokQ).append("""">""")
                sb.append("""<span class="thumb-wrap"><span class="icon">""").append(fileIcon(eName)).append("</span></span>")
                sb.append("""<div class="info"><div class="name">""").append(escHtml(eName))
                sb.append("""</div><div class="meta">""").append(escHtml(meta))
                sb.append("</div></div></a>")
                // "Play on TV" — only for streamable media. Casts this file to a DroidServe TV
                // app on the same network via /api/cast; the TV opens it in its own native
                // player (VLC/MX/etc). data-path carries the encoded relative path.
                if (isVideo || isAudio) {
                    val rawPath = if (urlPath.isEmpty()) eName else "${urlPath.trimEnd('/')}/$eName"
                    sb.append("""<button class="btn-tv" data-path="""").append('"').append(escHtml(rawPath))
                    sb.append("""" title="Play on TV" aria-label="Play on TV">📺</button>""")
                }
                // Download button pinned to the right edge. Shown only when downloads are
                // allowed; with the option off the server is preview/stream-only.
                if (allowDownload) {
                    sb.append("""<a class="btn-dl" href="/""").append(href)
                    sb.append("""?dl=1""").append(tokAmp).append("""" download="${escHtml(eName)}" title="Download" aria-label="Download ${escHtml(eName)}">⬇</a>""")
                }
                sb.append("</div>")
            }
        }

        // Close the sortable list and add the "no match" placeholder BEFORE the footer,
        // so the footer sits outside #ls and is never touched by sort/filter.
        sb.append("""</div><div class="empty" id="em" style="display:none">No items match your filter.</div>""")

        // Transparency footer — counts, totals, and live server/network facts (nothing hidden)
        val fileCount  = entries.count { !it.isDirectory }
        val dirCount   = entries.size - fileCount
        val totalBytes = entries.filter { !it.isDirectory }.sumOf { it.size }
        sb.append("""<div class="info-foot">""")
        sb.append("""<div class="info-h">ℹ️ Server info</div>""")
        sb.append("$fileCount files · $dirCount folders · ")
        sb.append(escHtml(formatSize(totalBytes))).append(" in this folder<br>")
        for ((k, v) in serverFacts) {
            sb.append(escHtml(k)).append(": ").append(escHtml(v)).append("<br>")
        }
        sb.append("Generated ").append(escHtml(formatDate(System.currentTimeMillis())))
        sb.append("</div>")

        sb.append(HTML_TAIL)
        return sb.toString()
    }

    private val DATE_FMT = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
    fun formatDate(ms: Long): String = if (ms <= 0L) "" else DATE_FMT.format(java.util.Date(ms))

    // ----------------------------------------------------------------------
    // Subtitles — browsers only accept WebVTT for <track>. SubRip (.srt) is by far the most
    // common sidecar format, so convert it on the fly. Pure/String-in-String-out so it is
    // unit-testable on the JVM with no Android Context.
    // ----------------------------------------------------------------------
    private val SRT_TIME = Regex("""(\d{1,2}:\d{2}:\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}:\d{2}:\d{2})[,.](\d{1,3})""")

    /**
     * Convert SubRip (.srt) text to WebVTT (.vtt). Handles the two things that actually break
     * browser parsing: the "WEBVTT" header must be present, and cue timestamps use '.' (not the
     * SubRip ','). Sequence-number lines are dropped (optional in VTT). A leading UTF-8 BOM is
     * stripped. Input that is already VTT is passed through with just a guaranteed header.
     */
    fun srtToVtt(srt: String): String {
        var text = srt
        if (text.isNotEmpty() && text[0] == '\uFEFF') text = text.substring(1)   // strip BOM
        // Normalise line endings so cue detection is CRLF/LF agnostic.
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = StringBuilder(text.length + 32)
        out.append("WEBVTT\n\n")
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val m = SRT_TIME.find(line)
            if (m != null) {
                // Drop a bare numeric index line immediately preceding the timestamp (already emitted).
                out.append("${m.groupValues[1]}.${padMs(m.groupValues[2])} --> ${m.groupValues[3]}.${padMs(m.groupValues[4])}\n")
            } else if (line.trim().all { it.isDigit() } && line.isNotBlank() &&
                       i + 1 < lines.size && SRT_TIME.containsMatchIn(lines[i + 1])) {
                // Skip the SubRip sequence number (the next line is its timestamp).
            } else {
                out.append(line).append('\n')
            }
            i++
        }
        return out.toString()
    }

    // Milliseconds in SubRip may be 1–3 digits; VTT wants exactly 3.
    private fun padMs(ms: String): String = when (ms.length) {
        1 -> "${ms}00"; 2 -> "${ms}0"; 3 -> ms; else -> ms.substring(0, 3)
    }


    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------
    private fun fileIcon(name: String): String {
        val dot = name.lastIndexOf('.')
        val ext = if (dot >= 0) name.substring(dot + 1).lowercase() else ""
        return when (ext) {
            "jpg","jpeg","png","gif","webp","svg","ico" -> "🖼️"
            "mp4","mkv","avi","mov","webm"              -> "🎬"
            "mp3","wav","ogg","flac","aac","m4a"        -> "🎵"
            "pdf"                                       -> "📄"
            "zip","tar","gz","7z","rar"                 -> "🗜️"
            "apk"                                       -> "📱"
            "txt","md","log","csv"                      -> "📝"
            "json","xml","yaml","toml","ini"            -> "⚙️"
            "html","htm","css","js","ts","kt","py","sh" -> "💻"
            else                                        -> "📄"
        }
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 1_024         -> "$bytes B"
        bytes < 1_048_576     -> "%.1f KB".format(bytes / 1_024.0)
        bytes < 1_073_741_824 -> "%.1f MB".format(bytes / 1_048_576.0)
        else                  -> "%.2f GB".format(bytes / 1_073_741_824.0)
    }

    fun escHtml(s: String): String {
        var first = -1
        for (i in s.indices) {
            val c = s[i]
            if (c == '&' || c == '<' || c == '>' || c == '"') { first = i; break }
        }
        if (first < 0) return s
        val out = StringBuilder(s.length + 16).append(s, 0, first)
        for (i in first until s.length) {
            when (s[i]) {
                '&'  -> out.append("&amp;")
                '<'  -> out.append("&lt;")
                '>'  -> out.append("&gt;")
                '"'  -> out.append("&quot;")
                else -> out.append(s[i])
            }
        }
        return out.toString()
    }

    // Minimal JSON string-body escaper for values embedded in a data-* attribute's JSON.
    // (The whole attribute is additionally HTML-escaped by escHtml at the call site.)
    private fun jsonStr(s: String): String {
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

    private val HEX = "0123456789ABCDEF".toCharArray()
    /**
     * Percent-encode a single path segment per RFC 3986: everything that is not an
     * unreserved character (A–Z a–z 0–9 - . _ ~) is encoded from its UTF-8 bytes.
     * This guarantees the result is safe both as a URL segment and inside a
     * double-quoted HTML attribute (no raw " < > & space), closing the XSS hole.
     */
    internal fun encodeSeg(s: String): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size + 8)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            val unreserved = c in 0x30..0x39 || c in 0x41..0x5A || c in 0x61..0x7A ||
                c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
            if (unreserved) {
                out.append(c.toChar())
            } else {
                out.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
            }
        }
        return out.toString()
    }

    /** Percent-encode each non-empty segment of a slash-separated path. */
    private fun encodePath(path: String): String =
        path.split('/').filter { it.isNotEmpty() }.joinToString("/") { encodeSeg(it) }

    /**
     * Emit a clickable breadcrumb trail: 🏠 Home / sub / sub / current.
     * Each ancestor links to its own directory; the current folder is plain text.
     */
    private fun buildBreadcrumb(sb: StringBuilder, urlPath: String) {
        val segs = urlPath.split('/').filter { it.isNotEmpty() }
        if (segs.isEmpty()) {
            sb.append("""<span class="cur">🏠 Home</span>""")
            return
        }
        sb.append("""<a href="/">🏠 Home</a>""")
        val acc = StringBuilder()
        for ((i, seg) in segs.withIndex()) {
            acc.append('/').append(encodeSeg(seg))
            sb.append("""<span class="sep">/</span>""")
            if (i == segs.lastIndex) {
                sb.append("""<span class="cur">""").append(escHtml(seg)).append("</span>")
            } else {
                sb.append("""<a href="""").append(acc).append("""">""").append(escHtml(seg)).append("</a>")
            }
        }
    }

    // ----------------------------------------------------------------------
    // HTML template split into static chunks — no per-request .replace()
    // ----------------------------------------------------------------------
    private val HTML_HEAD_1 = """<!DOCTYPE html>
<html lang="en"><head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#0f172a">
<link rel="icon" href="data:image/svg+xml,%3Csvg%20xmlns='http://www.w3.org/2000/svg'%20viewBox='0%200%20100%20100'%3E%3Ctext%20y='.9em'%20font-size='90'%3E📡%3C/text%3E%3C/svg%3E">
<title>"""

    private val HTML_HEAD_2A = """</title>
<style>
*{box-sizing:border-box;margin:0;padding:0;-webkit-tap-highlight-color:transparent}
:root{--bg:#0f172a;--sf:#1e293b;--sf2:#243449;--bd:#334155;--ac:#38bdf8;--tx:#e2e8f0;--mu:#7c8aa0;--gz:#0ea5e9;--dl:#10b981;--hv:#1e3a5f;--sh:0 1px 3px rgba(0,0,0,.3)}
html[data-t=light]{--bg:#f1f5f9;--sf:#ffffff;--sf2:#f8fafc;--bd:#dbe2ea;--ac:#0284c7;--tx:#0f172a;--mu:#64748b;--gz:#0284c7;--dl:#059669;--hv:#e0f2fe;--sh:0 1px 3px rgba(0,0,0,.08)}
body{font-family:system-ui,-apple-system,sans-serif;background:var(--bg);color:var(--tx);min-height:100vh;padding-bottom:env(safe-area-inset-bottom,0);transition:background .2s,color .2s}
header{background:var(--sf);padding:12px 16px;border-bottom:1px solid var(--bd);position:sticky;top:0;z-index:100;box-shadow:var(--sh)}
.hr{display:flex;align-items:center;gap:10px;margin-bottom:8px}
h1{font-size:18px;color:var(--ac);font-weight:800;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.tgl{background:var(--sf2);border:1px solid var(--bd);color:var(--tx);border-radius:50%;width:34px;height:34px;font-size:16px;cursor:pointer;flex-shrink:0;display:flex;align-items:center;justify-content:center;transition:border-color .12s}
.tgl:hover{border-color:var(--ac)}
input[type=search]{width:100%;padding:10px 14px;border-radius:10px;border:1.5px solid var(--bd);background:var(--bg);color:var(--tx);font-size:15px;outline:none;transition:border-color .15s}
input[type=search]:focus{border-color:var(--ac)}
.crumb{font-size:12px;color:var(--mu);margin-top:8px;word-break:break-all;line-height:1.6}
.crumb a{color:var(--ac);text-decoration:none}
.crumb a:hover{text-decoration:underline}
.crumb .sep{color:var(--bd);margin:0 5px}
.crumb .cur{color:var(--tx);font-weight:600}
main{padding:12px;max-width:1200px;margin:0 auto}
/* TV overscan: many TVs crop screen edges — add breathing room on large viewports */
@media (min-width:1000px){main{padding:24px 40px}header{padding:16px 40px}}
.tb{display:flex;gap:6px;margin-bottom:10px;align-items:center;flex-wrap:wrap}
.st{font-size:11px;color:var(--mu);flex:1;min-width:80px}
.sb{background:var(--sf);border:1px solid var(--bd);color:var(--tx);border-radius:7px;padding:6px 12px;font-size:12px;cursor:pointer;text-decoration:none;transition:border-color .12s,color .12s,background .12s}
.sb:hover,.sb.on{border-color:var(--ac);color:var(--ac)}
.sb.zipall{background:var(--gz);color:#fff;border-color:var(--gz);font-weight:600}
.sb.zipall:hover{filter:brightness(1.12);color:#fff}
.list{display:flex;flex-direction:column;gap:5px}
.parent{margin-bottom:5px}
.item{display:flex;align-items:stretch;background:var(--sf);border:1px solid var(--bd);border-radius:11px;overflow:hidden;transition:border-color .12s,background .12s,transform .08s;min-height:56px;box-shadow:var(--sh)}
.item:hover{border-color:var(--ac);background:var(--hv)}
.item:active{transform:scale(.995)}
/* TV / D-pad remote: strong visible focus ring so you can see the cursor position */
a:focus-visible,button:focus-visible,input:focus-visible{outline:none}
.item:focus-within,.sb:focus-visible,.tgl:focus-visible,.crumb a:focus-visible{border-color:var(--ac);background:var(--hv);box-shadow:0 0 0 3px var(--ac)}
.item-main:focus-visible,.btn-zip:focus-visible,.btn-dl:focus-visible{box-shadow:inset 0 0 0 3px var(--ac)}
.item-main{flex:1;min-width:0;display:flex;align-items:center;gap:12px;padding:10px 12px;text-decoration:none;color:var(--tx)}
.icon{font-size:22px;min-width:26px;text-align:center;flex-shrink:0}
.info{flex:1;min-width:0}
.name{font-weight:500;font-size:14px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;line-height:1.3}
.meta{font-size:11px;color:var(--mu);margin-top:2px}
.btn-zip,.btn-dl,.btn-tv{flex-shrink:0;display:flex;align-items:center;justify-content:center;padding:0 14px;font-weight:700;text-decoration:none;border-left:1px solid var(--bd);white-space:nowrap;cursor:pointer;transition:filter .12s}
.btn-zip{background:var(--gz);color:#fff;min-width:66px;font-size:12px}
.btn-zip:hover{filter:brightness(1.15)}
.btn-dl{background:var(--dl);color:#fff;min-width:48px;font-size:18px}
.btn-dl:hover{filter:brightness(1.15)}
.btn-tv{background:var(--sf2);color:var(--tx);min-width:48px;font-size:17px;border:none;border-left:1px solid var(--bd);cursor:pointer}
.btn-tv:hover{filter:brightness(1.2);color:var(--ac)}
.btn-tv.ok{background:var(--dl);color:#fff}
.btn-tv.err{background:#ef4444;color:#fff}
.empty{text-align:center;color:var(--mu);padding:60px 20px;font-size:14px}
.info-foot{margin-top:16px;padding:14px;border:1px solid var(--bd);border-radius:11px;background:var(--sf);font-size:11px;color:var(--mu);line-height:1.7;word-break:break-all;box-shadow:var(--sh)}
.info-h{color:var(--ac);font-weight:700;margin-bottom:6px;font-size:12px}
/* Android-TV / D-pad: no mouse, so hide the pointer entirely for a clean 10-foot UI */
body.tv,body.tv *{cursor:none!important}
::-webkit-scrollbar{width:6px}::-webkit-scrollbar-track{background:var(--bg)}::-webkit-scrollbar-thumb{background:var(--bd);border-radius:3px}
/* ── In-browser media player overlay ─────────────────────────────────────── */
.pl-ov{position:fixed;inset:0;z-index:1000;background:rgba(0,0,0,.86);display:none;flex-direction:column;align-items:center;justify-content:center;padding:16px;backdrop-filter:blur(4px)}
.pl-ov.on{display:flex}
.pl-box{background:var(--sf);border:1px solid var(--bd);border-radius:14px;box-shadow:0 10px 40px rgba(0,0,0,.5);width:min(960px,96vw);max-height:94vh;display:flex;flex-direction:column;overflow:hidden}
.pl-hd{display:flex;align-items:center;gap:10px;padding:10px 14px;border-bottom:1px solid var(--bd)}
.pl-ttl{flex:1;min-width:0;font-size:14px;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:var(--tx)}
.pl-x{background:var(--sf2);border:1px solid var(--bd);color:var(--tx);border-radius:8px;width:34px;height:34px;font-size:16px;cursor:pointer;flex-shrink:0}
.pl-x:hover{border-color:var(--ac);color:var(--ac)}
.pl-stage{background:#000;display:flex;align-items:center;justify-content:center;min-height:200px;position:relative}
.pl-stage video{width:100%;max-height:70vh;display:block;background:#000}
.pl-stage audio{width:92%;margin:28px auto}
.pl-fallback{display:none;flex-direction:column;align-items:center;gap:14px;padding:40px 24px;text-align:center;color:var(--tx)}
.pl-fallback.on{display:flex}
.pl-fallback .big{font-size:40px}
.pl-fallback .msg{font-size:14px;color:var(--mu);max-width:440px;line-height:1.5}
.pl-actions{display:flex;gap:8px;flex-wrap:wrap;justify-content:center}
.pl-btn{background:var(--gz);color:#fff;border:none;border-radius:9px;padding:11px 18px;font-size:14px;font-weight:600;cursor:pointer;text-decoration:none;display:inline-flex;align-items:center;gap:7px;transition:filter .12s}
.pl-btn:hover{filter:brightness(1.13)}
.pl-btn.alt{background:var(--sf2);color:var(--tx);border:1px solid var(--bd)}
.pl-btn.alt:hover{border-color:var(--ac);color:var(--ac);filter:none}
.pl-ft{display:flex;align-items:center;gap:8px;padding:10px 14px;border-top:1px solid var(--bd);flex-wrap:wrap}
.pl-nav{background:var(--sf2);border:1px solid var(--bd);color:var(--tx);border-radius:8px;padding:8px 14px;font-size:13px;cursor:pointer;transition:border-color .12s}
.pl-nav:hover:not(:disabled){border-color:var(--ac);color:var(--ac)}
.pl-nav:disabled{opacity:.4;cursor:default}
.pl-ext{margin-left:auto;font-size:12px;color:var(--mu);display:inline-flex;gap:8px;align-items:center;flex-wrap:wrap}
.pl-ext a{color:var(--ac);text-decoration:none;border:1px solid var(--bd);border-radius:7px;padding:6px 10px}
.pl-ext a:hover{border-color:var(--ac)}
/* ── Thumbnail slot (neutral in list mode; the hero image area in grid mode) ── */
.thumb-wrap{display:inline-flex;align-items:center;justify-content:center;flex-shrink:0}
/* ── Grid / gallery view ─────────────────────────────────────────────────── */
.list.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:12px}
@media(min-width:1000px){.list.grid{grid-template-columns:repeat(auto-fill,minmax(184px,1fr));gap:16px}}
.list.grid .item{flex-direction:column;align-items:stretch;min-height:0}
.list.grid .item-main{flex-direction:column;align-items:stretch;gap:0;padding:0}
.list.grid .thumb-wrap{width:100%;aspect-ratio:16/10;position:relative;background:var(--sf2);overflow:hidden}
.list.grid .gthumb{width:100%;height:100%;object-fit:cover;display:block}
.list.grid .icon{font-size:42px;min-width:0}
.list.grid .has-thumb .icon{display:none}
.list.grid .info{padding:9px 11px;min-width:0}
.list.grid .name{white-space:normal;max-height:2.6em;overflow:hidden}
.list.grid .badge{position:absolute;right:6px;bottom:6px;background:rgba(0,0,0,.72);color:#fff;font-size:11px;padding:2px 6px;border-radius:5px;font-weight:700}
.list.grid .btn-zip,.list.grid .btn-dl,.list.grid .btn-tv{border-left:none;border-top:1px solid var(--bd);padding:8px}
/* ── Image lightbox ───────────────────────────────────────────────────────── */
.lb-ov{position:fixed;inset:0;z-index:1100;background:rgba(0,0,0,.94);display:none;align-items:center;justify-content:center}
.lb-ov.on{display:flex}
.lb-img{max-width:94vw;max-height:84vh;object-fit:contain;transition:transform .15s;cursor:zoom-in;touch-action:none}
.lb-img.zoom{cursor:zoom-out}
.lb-top{position:absolute;top:0;left:0;right:0;display:flex;align-items:center;gap:10px;padding:12px 16px;background:linear-gradient(rgba(0,0,0,.6),transparent)}
.lb-ttl{flex:1;min-width:0;color:#fff;font-size:14px;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.lb-x{background:rgba(255,255,255,.14);border:none;color:#fff;border-radius:8px;width:38px;height:38px;font-size:18px;cursor:pointer}
.lb-x:hover,.lb-x:focus-visible{background:rgba(255,255,255,.28);outline:none}
.lb-nav{position:absolute;top:50%;transform:translateY(-50%);background:rgba(255,255,255,.12);border:none;color:#fff;width:52px;height:74px;font-size:34px;cursor:pointer;border-radius:10px}
.lb-nav:hover,.lb-nav:focus-visible{background:rgba(255,255,255,.26);outline:none}
.lb-prev{left:14px}.lb-next{right:14px}
.lb-info{position:absolute;left:0;right:0;bottom:58px;text-align:center;color:#cbd5e1;font-size:12px;padding:0 16px;text-shadow:0 1px 3px #000}
.lb-actions{position:absolute;bottom:14px;left:0;right:0;display:flex;gap:10px;justify-content:center}
.lb-btn{background:rgba(255,255,255,.14);border:none;color:#fff;border-radius:9px;padding:9px 16px;font-size:13px;font-weight:600;cursor:pointer}
.lb-btn:hover,.lb-btn:focus-visible{background:rgba(255,255,255,.28);outline:none}
/* ── Music library overlay ────────────────────────────────────────────────── */
.mu-ov{position:fixed;inset:0;z-index:1050;background:var(--bg);display:none;flex-direction:column}
.mu-ov.on{display:flex}
.mu-hd{display:flex;align-items:center;gap:10px;padding:12px 16px;border-bottom:1px solid var(--bd);background:var(--sf)}
.mu-ttl{font-size:16px;font-weight:800;color:var(--ac);white-space:nowrap}
.mu-hd input{flex:1;min-width:0;padding:8px 12px;border-radius:9px;border:1.5px solid var(--bd);background:var(--bg);color:var(--tx);font-size:14px;outline:none}
.mu-hd input:focus{border-color:var(--ac)}
.mu-body{flex:1;overflow:auto;padding:16px;max-width:1200px;margin:0 auto;width:100%}
.mu-albums{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:14px}
.mu-card{background:var(--sf);border:1px solid var(--bd);border-radius:12px;overflow:hidden;cursor:pointer;transition:border-color .12s}
.mu-card:hover,.mu-card:focus-visible{border-color:var(--ac);box-shadow:0 0 0 2px var(--ac);outline:none}
.mu-cov{width:100%;aspect-ratio:1;object-fit:cover;background:var(--sf2);display:block}
.mu-cap{padding:8px 10px}
.mu-nm{font-size:13px;font-weight:600;color:var(--tx);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.mu-ct{font-size:11px;color:var(--mu)}
.mu-back{display:inline-block;color:var(--ac);cursor:pointer;margin-bottom:12px;font-size:14px}
.mu-song{display:flex;align-items:center;gap:10px;padding:11px 12px;border:1px solid var(--bd);border-radius:10px;background:var(--sf);margin-bottom:6px;cursor:pointer;transition:border-color .12s}
.mu-song:hover,.mu-song:focus-visible{border-color:var(--ac);background:var(--hv);outline:none}
.mu-song.playing{border-color:var(--ac);color:var(--ac);font-weight:600}
.mu-bar{display:flex;align-items:center;gap:10px;padding:10px 16px;border-top:1px solid var(--bd);background:var(--sf)}
.mu-now{font-size:13px;color:var(--tx);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:26vw}
.mu-bar audio{flex:1;min-width:0;height:36px}
.mu-nav{background:var(--sf2);border:1px solid var(--bd);color:var(--tx);border-radius:8px;width:38px;height:38px;font-size:18px;cursor:pointer;flex-shrink:0}
.mu-nav:hover{border-color:var(--ac);color:var(--ac)}
/* ── Streaming links tab ──────────────────────────────────────────────────── */
.lk-hd .sb{flex-shrink:0}
.lk-top{display:flex;align-items:center;gap:10px;margin-bottom:12px}
.lk-top .c{flex:1;color:var(--mu);font-size:12px}
.lk-row{display:flex;align-items:center;gap:12px;padding:11px 12px;border:1px solid var(--bd);border-radius:10px;background:var(--sf);margin-bottom:6px}
.lk-row .lk-i{font-size:20px;flex-shrink:0}
.lk-row .lk-m{flex:1;min-width:0}
.lk-row .lk-nm{font-size:14px;color:var(--tx);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.lk-row .lk-u{font-size:11px;color:var(--mu);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.lk-del{background:var(--sf2);border:1px solid var(--bd);color:var(--tx);border-radius:7px;width:34px;height:34px;font-size:14px;cursor:pointer;flex-shrink:0}
.lk-del:hover{border-color:#ef4444;color:#ef4444}
.lk-empty{color:var(--mu);text-align:center;padding:40px 16px;font-size:14px;line-height:1.5}
</style></head>
<body>
<header>
<div class="hr"><h1>📡 """

    private val HTML_HEAD_2B = """</h1><button class="tgl" id="lkbtn" title="Streaming links" aria-label="Streaming links">🔗</button><button class="tgl" id="mubtn" title="Music" aria-label="Music">🎵</button><button class="tgl" id="tg" title="Toggle theme" aria-label="Toggle theme">🌙</button></div>
<input type="search" id="q" placeholder="Filter files in this folder…" aria-label="Filter files in this folder" autocomplete="off" autocorrect="off" spellcheck="false">
<nav class="crumb">"""

    private val HTML_HEAD_3 = """</nav>
</header>
<main>"""

    private val HTML_TAIL = """</main>
<div class="pl-ov" id="plov">
<div class="pl-box">
<div class="pl-hd"><div class="pl-ttl" id="plttl"></div><button class="pl-x" id="plx" title="Close (Esc)">✕</button></div>
<div class="pl-stage" id="plstage"></div>
<div class="pl-fallback" id="plfb">
<div class="big">🎬</div>
<div class="msg" id="plfbm"></div>
<div class="pl-actions" id="plfba"></div>
</div>
<div class="pl-ft">
<button class="pl-nav" id="plprev">‹ Prev</button>
<button class="pl-nav" id="plnext">Next ›</button>
<span class="pl-ext" id="plext"></span>
</div>
</div>
</div>
<div class="lb-ov" id="lbov">
<div class="lb-top"><span class="lb-ttl" id="lbttl"></span><button class="lb-x" id="lbx" title="Close (Esc)">✕</button></div>
<button class="lb-nav lb-prev" id="lbprev" title="Previous">‹</button>
<img class="lb-img" id="lbimg" alt="">
<button class="lb-nav lb-next" id="lbnext" title="Next">›</button>
<div class="lb-info" id="lbinfo"></div>
<div class="lb-actions"><button class="lb-btn" id="lbzoom">⤢ Zoom</button><button class="lb-btn" id="lbplay">▶ Slideshow</button><a class="lb-btn" id="lbopen" target="_blank" rel="noopener">↗ Open</a><a class="lb-btn" id="lbdl" download>⬇ Download</a></div>
</div>
<div class="mu-ov" id="muov">
<div class="mu-hd"><span class="mu-ttl">🎵 Music</span><input type="search" id="muq" placeholder="Search songs / albums…" aria-label="Search songs or albums" autocomplete="off" spellcheck="false"><button class="pl-x" id="mux" title="Close (Esc)">✕</button></div>
<div class="mu-body" id="mubody"></div>
<div class="mu-bar" id="mubar" style="display:none"><button class="mu-nav" id="muprev">‹</button><audio id="muaudio" controls preload="auto"></audio><button class="mu-nav" id="munext">›</button><span class="mu-now" id="munow"></span></div>
</div>
<div class="mu-ov" id="lkov">
<div class="mu-hd lk-hd"><span class="mu-ttl">🔗 Links</span><input type="url" id="lkin" placeholder="Paste a video / stream URL…" autocomplete="off" autocorrect="off" spellcheck="false" aria-label="Streaming URL to send to the TV"><button class="sb" id="lkadd">Send to TV</button><button class="pl-x" id="lkx" title="Close (Esc)">✕</button></div>
<div class="mu-body" id="lkbody"></div>
</div>
<script>
(function(){
// Theme toggle — persisted in localStorage, defaults to dark.
var root=document.documentElement,tg=document.getElementById('tg');
function applyTheme(t){root.setAttribute('data-t',t);tg.textContent=t==='light'?'☀️':'🌙';}
applyTheme(localStorage.getItem('ds-theme')||(window.matchMedia&&matchMedia('(prefers-color-scheme: light)').matches?'light':'dark'));
tg.addEventListener('click',function(){var t=root.getAttribute('data-t')==='light'?'dark':'light';localStorage.setItem('ds-theme',t);applyTheme(t);});

var ls=document.getElementById('ls'),st=document.getElementById('st'),em=document.getElementById('em'),q=document.getElementById('q');
var all=Array.from(ls.querySelectorAll('.item[data-name]')),orig=all.slice(),T=all.length;
function upd(){var v=0,s=q.value.toLowerCase();all.forEach(function(e){var ok=!s||e.dataset.name.toLowerCase().indexOf(s)>=0;e.style.display=ok?'':'none';if(ok)v++;});st.textContent=v+(v!==T?' of '+T:'')+' item'+(T!==1?'s':'');em.textContent=T===0?'This folder is empty.':'No items match your filter.';em.style.display=v===0?'':'none';}
upd();
var t;q.addEventListener('input',function(){clearTimeout(t);t=setTimeout(upd,60);});
// '/' focuses the filter box for quick keyboard search
document.addEventListener('keydown',function(e){if(e.key==='/'&&document.activeElement!==q){e.preventDefault();q.focus();}else if(e.key==='Escape'&&document.activeElement===q&&q.value){e.preventDefault();q.value='';upd();}});

// ── Opening files: hand every file to the OS default app (file-manager behaviour) ──
// On Android (phone AND TV), clicking a file fires an ACTION_VIEW intent with the file's
// MIME type, so the OS opens the default handler for that extension — a video player for
// mkv/mp4, an image viewer for jpg, a PDF reader, etc. — exactly like tapping a file in a
// file manager. On non-Android (desktop/laptop) the browser handles the plain link.
// The ⬇ button on the right stays download-only everywhere.
var ua=navigator.userAgent;
var isAndroid=/Android/i.test(ua);
// Hide the mouse cursor on TV / D-pad browsers for a clean 10-foot UI (no pointer device).
if(/TV|Leanback|CrKey|BRAVIA|AFT|MiTV|GoogleTV/i.test(ua)||(isAndroid&&!/Mobile/i.test(ua)))
document.body.classList.add('tv');

if(isAndroid){
document.querySelectorAll('.item[data-mime] .item-main').forEach(function(a){
a.addEventListener('click',function(e){
var abs=a.href,mime=a.closest('.item').dataset.mime||'';
var m=abs.match(/^(https?):\/\/([^#]*)$/i);if(!m)return;   // fall back to normal nav
e.preventDefault();
// intent:// with ACTION_VIEW + type → OS opens the default app for this file type.
// browser_fallback_url makes the browser just load the file if no app handles it,
// instead of failing silently.
var fb=encodeURIComponent(abs);
var it='intent://'+m[2]+'#Intent;scheme='+m[1]+';action=android.intent.action.VIEW';
if(mime)it+=';type='+mime;
it+=';S.browser_fallback_url='+fb+';end';
window.location.href=it;
});});
}

// ── In-browser media player (desktop / laptop / non-Android) ──────────────
// Clicking a video/audio row opens a modal player INSTEAD of navigating away. The browser
// plays everything it can decode natively (mp4/webm/mp3/ogg/wav...). For containers/codecs
// the browser can't decode (mkv/avi/mov, often flac) we can't magically enumerate installed
// apps from a sandbox — so we offer "Open in default player", which downloads a one-line .m3u
// playlist that the OS hands to VLC / Kodi / mpv / MPC-HC (whatever is the user's default),
// and that native player streams the URL with full codec support. Also always offers Download.
if(!isAndroid){
var media=all.filter(function(e){return e.dataset.play;});
var ov=document.getElementById('plov'),vs=document.getElementById('plstage'),
tt=document.getElementById('plttl'),fb=document.getElementById('plfb'),fbm=document.getElementById('plfbm'),
prevB=document.getElementById('plprev'),nextB=document.getElementById('plnext'),
extWrap=document.getElementById('plext');
var curEl=null,curIdx=-1;
var fba=document.getElementById('plfba');
function stop(){if(curEl){try{curEl.pause();}catch(_){}curEl.removeAttribute('src');try{curEl.load();}catch(_){}curEl.remove();curEl=null;}}
function close(){stop();ov.classList.remove('on');document.body.style.overflow='';}
function canPlay(el,mime){try{return !!(el.canPlayType&&el.canPlayType(mime).replace('no',''));}catch(_){return false;}}
function playAt(idx){
if(idx<0||idx>=media.length)return;
curIdx=idx;var it=media[idx];
var a=it.querySelector('.item-main'),url=a.getAttribute('href');
var mime=it.dataset.mime||'',name=it.dataset.name||'',kind=it.dataset.play;
tt.textContent=name;
stop();fb.classList.remove('on');vs.style.display='';
// External-player + download links (always available, work for every codec).
var m3u=url+(url.indexOf('?')>=0?'&':'?')+'m3u=1';
var dl=url+(url.indexOf('?')>=0?'&':'?')+'dl=1';
// Downloads may be disabled server-side (no .btn-dl rendered on any row) — respect that.
var dlOn=!!document.querySelector('.btn-dl');
var dlLink=dlOn?'<a class="pl-btn alt" href="'+dl+'" download>⬇ Download</a>':'';
extWrap.innerHTML='<a href="'+m3u+'">🎬 Open in default player</a>'+(dlOn?'<a href="'+dl+'" download>⬇ Download</a>':'');
// Prominent buttons for the fallback panel (used when the browser can't decode in-page).
fba.innerHTML='<a class="pl-btn" href="'+m3u+'">🎬 Open in default player</a>'+dlLink;
var el=document.createElement(kind==='a'?'audio':'video');
el.controls=true;el.autoplay=true;el.setAttribute('playsinline','');el.preload='auto';
el.setAttribute('crossorigin','anonymous');
var supported=canPlay(el,mime);
if(supported){
el.src=url;
// Attach sidecar subtitle tracks (served as WebVTT via ?vtt=1). First one shows by default.
if(kind==='v'&&it.dataset.subs){
try{var subs=JSON.parse(it.dataset.subs);
subs.forEach(function(su,si){var tr=document.createElement('track');tr.kind='subtitles';
tr.label=su.label;tr.srclang=(su.label||'sub').slice(0,5);tr.src=su.url;if(si===0)tr.default=true;
el.appendChild(tr);});
if(subs.length){el.addEventListener('loadedmetadata',function(){try{if(el.textTracks&&el.textTracks[0])el.textTracks[0].mode='showing';}catch(_){}}); }
}catch(_){}
}
vs.appendChild(el);curEl=el;
// Resume playback position: restore the last-saved offset for this exact URL and keep it
// updated as it plays, so re-opening a long video continues where you left off.
var rkey='ds-pos:'+url;
el.addEventListener('loadedmetadata',function(){try{var p=parseFloat(localStorage.getItem(rkey)||'0');
if(p>3&&el.duration&&p<el.duration-5)el.currentTime=p;}catch(_){}});
var last=0;el.addEventListener('timeupdate',function(){var t=el.currentTime;if(Math.abs(t-last)>=3){last=t;try{localStorage.setItem(rkey,String(t));}catch(_){}}});
el.addEventListener('ended',function(){try{localStorage.removeItem(rkey);}catch(_){}});
el.addEventListener('error',function(){showFallback();});
// When a track finishes, auto-advance to the next playable media in the folder.
el.addEventListener('ended',function(){if(curIdx+1<media.length)playAt(curIdx+1);});
try{el.play&&el.play().catch(function(){});}catch(_){}
}else{
showFallback();
}
updNav();
}
function showFallback(){
stop();vs.style.display='none';fb.classList.add('on');
fbm.textContent='Your browser can\u2019t play this format in-page. Open it in your default media player (VLC / Kodi / mpv), or download it.';
}
function updNav(){prevB.disabled=curIdx<=0;nextB.disabled=curIdx>=media.length-1;}
prevB.addEventListener('click',function(){if(curIdx>0)playAt(curIdx-1);});
nextB.addEventListener('click',function(){if(curIdx<media.length-1)playAt(curIdx+1);});
document.getElementById('plx').addEventListener('click',close);
ov.addEventListener('click',function(e){if(e.target===ov)close();});
document.addEventListener('keydown',function(e){if(!ov.classList.contains('on'))return;
if(e.key==='Escape')close();
else if(e.key==='ArrowRight'&&!e.target.matches('video,audio')){if(curIdx<media.length-1){e.preventDefault();playAt(curIdx+1);}}
else if(e.key==='ArrowLeft'&&!e.target.matches('video,audio')){if(curIdx>0){e.preventDefault();playAt(curIdx-1);}}
});
media.forEach(function(it){
it.querySelector('.item-main').addEventListener('click',function(e){
e.preventDefault();
ov.classList.add('on');document.body.style.overflow='hidden';
playAt(media.indexOf(it));
});});

// ── Image lightbox (prev/next, zoom, slideshow, swipe, EXIF) ──────────────
var imgs=all.filter(function(e){return (e.dataset.mime||'').indexOf('image/')===0;});
if(imgs.length){
var lbov=document.getElementById('lbov'),lbimg=document.getElementById('lbimg'),
lbttl=document.getElementById('lbttl'),lbinfo=document.getElementById('lbinfo'),
lbprev=document.getElementById('lbprev'),lbnext=document.getElementById('lbnext'),
lbplay=document.getElementById('lbplay');
var lbIdx=-1,lbZoom=false,lbSlide=null,lbSx=0;
function lbMeta(t){return t.replace('/thumb/','/meta/').replace(/([?&])w=\d+(&|$)/,function(m,p1,p2){return p2==='&'?p1:'';});}
function lbStop(){if(lbSlide){clearInterval(lbSlide);lbSlide=null;lbplay.textContent='▶ Slideshow';}}
function lbShow(i){
if(i<0||i>=imgs.length)return;lbIdx=i;lbZoom=false;
var it=imgs[i],a=it.querySelector('.item-main');
lbimg.classList.remove('zoom');lbimg.style.transform='';
var hrefimg=a.getAttribute('href');lbimg.src=hrefimg;lbttl.textContent=it.dataset.name||'';lbinfo.textContent='';
var lo=document.getElementById('lbopen');if(lo)lo.href=hrefimg;
var ld=document.getElementById('lbdl');if(ld){if(document.querySelector('.btn-dl')){ld.style.display='';ld.href=hrefimg+(hrefimg.indexOf('?')>=0?'&':'?')+'dl=1';ld.setAttribute('download',it.dataset.name||'');}else ld.style.display='none';}
lbprev.style.visibility=i>0?'':'hidden';lbnext.style.visibility=i<imgs.length-1?'':'hidden';
var t=it.dataset.thumb;
if(t){fetch(lbMeta(t),{credentials:'same-origin'}).then(function(r){return r.ok?r.json():null;}).then(function(m){
if(!m||lbIdx!==i)return;var p=[];if(m.width>0)p.push(m.width+'×'+m.height);
if(m.exif){if(m.exif.Model)p.push(m.exif.Model);if(m.exif.DateTime)p.push(m.exif.DateTime);}
lbinfo.textContent=p.join('  ·  ');}).catch(function(){});}
}
function lbOpen(i){lbov.classList.add('on');document.body.style.overflow='hidden';lbShow(i);}
function lbClose(){lbov.classList.remove('on');document.body.style.overflow='';lbimg.src='';lbStop();}
function lbTog(){lbZoom=!lbZoom;lbimg.classList.toggle('zoom',lbZoom);lbimg.style.transform=lbZoom?'scale(2)':'';}
imgs.forEach(function(it){it.querySelector('.item-main').addEventListener('click',function(e){e.preventDefault();lbOpen(imgs.indexOf(it));});});
lbprev.addEventListener('click',function(){lbShow(lbIdx-1);});
lbnext.addEventListener('click',function(){lbShow(lbIdx+1);});
document.getElementById('lbx').addEventListener('click',lbClose);
document.getElementById('lbzoom').addEventListener('click',lbTog);
lbimg.addEventListener('click',lbTog);
lbov.addEventListener('click',function(e){if(e.target===lbov)lbClose();});
lbplay.addEventListener('click',function(){if(lbSlide){lbStop();}else{lbplay.textContent='⏸ Stop';lbSlide=setInterval(function(){lbShow(lbIdx<imgs.length-1?lbIdx+1:0);},3000);}});
document.addEventListener('keydown',function(e){if(!lbov.classList.contains('on'))return;
e.stopImmediatePropagation();
if(e.key==='Escape')lbClose();else if(e.key==='ArrowRight'){e.preventDefault();lbShow(lbIdx+1);}else if(e.key==='ArrowLeft'){e.preventDefault();lbShow(lbIdx-1);}});
lbimg.addEventListener('touchstart',function(e){lbSx=e.touches[0].clientX;},{passive:true});
lbov.addEventListener('touchend',function(e){var dx=e.changedTouches[0].clientX-lbSx;if(Math.abs(dx)>50)lbShow(dx<0?lbIdx+1:lbIdx-1);});
}
}

// ── Play on TV: push this media file to a DroidServe TV app on the network ──
// POSTs the file's relative path to /api/cast; the phone resolves it to an absolute
// token-carrying URL and hands it to the TV, which opens it in its own native player.
document.querySelectorAll('.btn-tv[data-path]').forEach(function(b){
b.addEventListener('click',function(e){
e.preventDefault();e.stopPropagation();
var p=b.getAttribute('data-path');
b.disabled=true;
fetch('/api/cast?path='+encodeURIComponent(p),{credentials:'same-origin'})
.then(function(r){return r.ok;})
.then(function(ok){
b.classList.add(ok?'ok':'err');b.textContent=ok?'✅':'⚠️';
setTimeout(function(){b.classList.remove('ok','err');b.textContent='📺';b.disabled=false;},1500);
})
.catch(function(){b.classList.add('err');b.textContent='⚠️';
setTimeout(function(){b.classList.remove('err');b.textContent='📺';b.disabled=false;},1500);});
});});
document.querySelectorAll('.sb[data-s]').forEach(function(b){
b.addEventListener('click',function(){
document.querySelectorAll('.sb[data-s]').forEach(function(x){x.classList.remove('on');});b.classList.add('on');
var m=b.dataset.s,arr;
// Folders always stay grouped before files; the chosen order applies within each group.
function grp(e){return e.classList.contains('dir')?0:1;}
if(m==='d')arr=orig.slice();
else if(m==='n')arr=all.slice().sort(function(a,b){return grp(a)-grp(b)||a.dataset.name.localeCompare(b.dataset.name,undefined,{sensitivity:'base'});});
else if(m==='t')arr=all.slice().sort(function(a,b){return grp(a)-grp(b)||Number(b.dataset.date||0)-Number(a.dataset.date||0);});
else arr=all.slice().sort(function(a,b){return grp(a)-grp(b)||Number(b.dataset.size||0)-Number(a.dataset.size||0);});
arr.forEach(function(e){ls.appendChild(e);});
});});

// ── Grid / gallery view + lazy thumbnails + media badges ──────────────────
(function(){
var tg=document.getElementById('viewtg');if(!tg)return;
var obs=null;
function metaUrlOf(t){return t.replace('/thumb/','/meta/').replace(/([?&])w=\d+(&|$)/,function(m,p1,p2){return p2==='&'?p1:'';});}
function loadThumb(it){
var url=it.dataset.thumb;if(!url)return;
var wrap=it.querySelector('.thumb-wrap');if(!wrap||wrap.querySelector('.gthumb'))return;
var img=document.createElement('img');img.className='gthumb';img.loading='lazy';img.decoding='async';img.alt='';
img.onload=function(){it.classList.add('has-thumb');};img.onerror=function(){img.remove();};
img.src=url;wrap.appendChild(img);
fetch(metaUrlOf(url),{credentials:'same-origin'}).then(function(r){return r.ok?r.json():null;}).then(function(m){
if(!m)return;var b='';
if(m.durationMs>0){var s=Math.round(m.durationMs/1000);b=Math.floor(s/60)+':'+('0'+(s%60)).slice(-2);}
else if(m.width>0&&m.height>0){b=m.width+'×'+m.height;}
if(b){var sp=document.createElement('span');sp.className='badge';sp.textContent=b;wrap.appendChild(sp);}
}).catch(function(){});
}
function applyView(v){
if(v==='grid'){ls.classList.add('grid');tg.textContent='☰ List';
if(!obs)obs=new IntersectionObserver(function(es,o){es.forEach(function(en){if(en.isIntersecting){o.unobserve(en.target);loadThumb(en.target);}});},{rootMargin:'300px'});
all.forEach(function(it){if(it.dataset.thumb)obs.observe(it);});
}else{ls.classList.remove('grid');tg.textContent='⊞ Grid';}
}
tg.addEventListener('click',function(){var v=ls.classList.contains('grid')?'list':'grid';localStorage.setItem('ds-view',v);applyView(v);});
applyView(localStorage.getItem('ds-view')||'grid');
})();

// ── TV / D-pad remote navigation ─────────────────────────────────────────
// Arrow keys move focus spatially between focusable controls; Enter activates.
// Works with Android TV browsers, Fire TV, and any keyboard.
function focusables(){return Array.from(document.querySelectorAll('a[href],button,input[type=search]')).filter(function(el){return el.offsetParent!==null;});}
function nav(dir){
var els=focusables();if(!els.length)return;
var cur=document.activeElement,ci=els.indexOf(cur);
if(ci<0){els[0].focus();return;}
var cr=cur.getBoundingClientRect(),best=null,bestD=1e9;
els.forEach(function(el){if(el===cur)return;var r=el.getBoundingClientRect();
var dx=(r.left+r.width/2)-(cr.left+cr.width/2),dy=(r.top+r.height/2)-(cr.top+cr.height/2);
var ok=(dir==='up'&&dy<-4)||(dir==='down'&&dy>4)||(dir==='left'&&dx<-4)||(dir==='right'&&dx>4);
if(!ok)return;
// Prefer aligned candidates: weight the off-axis distance heavily.
var d=(dir==='up'||dir==='down')?Math.abs(dy)+Math.abs(dx)*2:Math.abs(dx)+Math.abs(dy)*2;
if(d<bestD){bestD=d;best=el;}});
if(best){best.focus();best.scrollIntoView({block:'nearest'});}
}
document.addEventListener('keydown',function(e){
var k=e.key;
if(k==='ArrowUp'||k==='ArrowDown'||k==='ArrowLeft'||k==='ArrowRight'){
if(document.activeElement===q&&(k==='ArrowLeft'||k==='ArrowRight'))return; // let caret move in search box
e.preventDefault();nav(k.slice(5).toLowerCase());
}else if(k==='Enter'&&document.activeElement&&document.activeElement.tagName==='A'){
// Anchors already activate on Enter in most browsers; this is a safe fallback.
}
});
// Auto-focus the first file/folder so a remote has an immediate cursor.
(function(){var f=document.querySelector('.item .item-main');if(f)f.focus();})();
})();
</script>
<script>
// ── Music library tab: folder-grouped albums, lazy cover art, FLAC streaming ──
(function(){
var btn=document.getElementById('mubtn'),ov=document.getElementById('muov'),body=document.getElementById('mubody'),
q=document.getElementById('muq'),bar=document.getElementById('mubar'),audio=document.getElementById('muaudio'),now=document.getElementById('munow');
if(!btn)return;
var albums=null,queue=[],qi=-1,curAlbum=null;
function esc(t){var d=document.createElement('div');d.textContent=t||'';return d.innerHTML;}
function openOv(){ov.classList.add('on');document.body.style.overflow='hidden';if(albums===null)load();else if(!curAlbum)render();}
function closeOv(){ov.classList.remove('on');document.body.style.overflow='';}
btn.addEventListener('click',openOv);
document.getElementById('mux').addEventListener('click',closeOv);
function load(){
body.innerHTML='<div style="color:var(--mu)">Loading music…</div>';
fetch('/api/music',{credentials:'same-origin'}).then(function(r){return r.ok?r.json():null;}).then(function(d){
albums=(d&&d.albums)||[];render();
}).catch(function(){body.innerHTML='<div style="color:#f87171">Failed to load music</div>';});
}
function filtered(){var s=(q.value||'').toLowerCase();if(!s)return albums;
return albums.filter(function(a){return a.name.toLowerCase().indexOf(s)>=0||a.songs.some(function(x){return x.name.toLowerCase().indexOf(s)>=0;});});}
function render(){
curAlbum=null;var list=filtered();
if(!list||!list.length){body.innerHTML='<div style="color:var(--mu)">No music found in the shared folder.</div>';return;}
var h='<div class="mu-albums">';
list.forEach(function(a,i){h+='<div class="mu-card" tabindex="0" data-i="'+i+'"><img class="mu-cov" loading="lazy" src="'+a.cover+'" onerror="this.style.visibility=\'hidden\'"><div class="mu-cap"><div class="mu-nm">'+esc(a.name)+'</div><div class="mu-ct">'+a.songs.length+' songs</div></div></div>';});
body.innerHTML=h+'</div>';
Array.prototype.forEach.call(body.querySelectorAll('.mu-card'),function(c){
function go(){showAlbum(list[+c.dataset.i]);}
c.addEventListener('click',go);c.addEventListener('keydown',function(e){if(e.key==='Enter')go();});});
}
function showAlbum(a){
curAlbum=a;var h='<div class="mu-back" id="muback" tabindex="0">‹ Albums</div><h2 style="font-size:17px;margin:0 0 10px">'+esc(a.name)+'</h2>';
a.songs.forEach(function(s,i){h+='<div class="mu-song" tabindex="0" data-i="'+i+'">▶&nbsp;&nbsp;'+esc(s.name)+'</div>';});
body.innerHTML=h;
document.getElementById('muback').addEventListener('click',render);
Array.prototype.forEach.call(body.querySelectorAll('.mu-song'),function(el){
function go(){playFrom(a,+el.dataset.i);}
el.addEventListener('click',go);el.addEventListener('keydown',function(e){if(e.key==='Enter')go();});});
}
function mark(){Array.prototype.forEach.call(body.querySelectorAll('.mu-song'),function(el,idx){el.classList.toggle('playing',curAlbum&&queue===curAlbum.songs&&idx===qi);});}
function playFrom(a,i){queue=a.songs;qi=i;bar.style.display='flex';var s=queue[i];now.textContent=s.name;audio.src=s.url;audio.play().catch(function(){});mark();}
function step(d){if(qi+d>=0&&qi+d<queue.length){qi+=d;var s=queue[qi];now.textContent=s.name;audio.src=s.url;audio.play().catch(function(){});mark();}}
document.getElementById('muprev').addEventListener('click',function(){step(-1);});
document.getElementById('munext').addEventListener('click',function(){step(1);});
audio.addEventListener('ended',function(){step(1);});
q.addEventListener('input',function(){if(!curAlbum)render();});
document.addEventListener('keydown',function(e){if(ov.classList.contains('on')&&e.key==='Escape')closeOv();});
})();
</script>
<script>
// ── Streaming links tab: paste a URL; it appears on the TV instantly for the user to open ──
(function(){
var btn=document.getElementById('lkbtn'),ov=document.getElementById('lkov'),body=document.getElementById('lkbody'),
inp=document.getElementById('lkin'),addB=document.getElementById('lkadd');
if(!btn)return;
var ver=-1,open=false,polling=false;
function esc(t){var d=document.createElement('div');d.textContent=t||'';return d.innerHTML;}
function icon(u){if(/^magnet:/i.test(u))return'🧲';if(/\.(mp3|flac|aac|ogg|wav|m4a|opus)(\?|$)/i.test(u))return'🎵';return'🎬';}
function render(d){
ver=d.version;var links=d.links||[];
var h='<div class="lk-top"><span class="c">'+links.length+' link'+(links.length!==1?'s':'')+' · shown on your TV</span>';
if(links.length)h+='<button class="sb" id="lkclr">Clear all</button>';
h+='</div>';
if(!links.length)h+='<div class="lk-empty">No links yet.<br>Paste a URL above and it appears on your TV instantly.</div>';
links.forEach(function(l){
h+='<div class="lk-row"><span class="lk-i">'+icon(l.url)+'</span><div class="lk-m"><div class="lk-nm">'+esc(l.label)+'</div><div class="lk-u">'+esc(l.url)+'</div></div><button class="lk-del" data-id="'+esc(l.id)+'" aria-label="Remove">✕</button></div>';
});
body.innerHTML=h;
var c=document.getElementById('lkclr');if(c)c.addEventListener('click',function(){api('/api/links/clear');});
Array.prototype.forEach.call(body.querySelectorAll('.lk-del'),function(b){b.addEventListener('click',function(){api('/api/links/remove?id='+encodeURIComponent(b.dataset.id));});});
}
function api(u){return fetch(u,{credentials:'same-origin'}).then(function(r){return r.ok?r.json():null;}).then(function(d){if(d)render(d);}).catch(function(){});}
function load(){api('/api/links');}
function send(){var u=(inp.value||'').trim();if(!u)return;inp.value='';api('/api/links/add?url='+encodeURIComponent(u));}
function openOv(){ov.classList.add('on');document.body.style.overflow='hidden';open=true;load();poll();setTimeout(function(){try{inp.focus();}catch(e){}},50);}
function closeOv(){ov.classList.remove('on');document.body.style.overflow='';open=false;}
btn.addEventListener('click',openOv);
document.getElementById('lkx').addEventListener('click',closeOv);
addB.addEventListener('click',send);
inp.addEventListener('keydown',function(e){if(e.key==='Enter'){e.preventDefault();send();}});
document.addEventListener('keydown',function(e){if(ov.classList.contains('on')&&e.key==='Escape')closeOv();});
// Live updates via long-poll while the tab is open (stops as soon as it closes).
function poll(){if(polling)return;polling=true;(function loop(){
if(!open){polling=false;return;}
fetch('/api/links/poll?since='+ver,{credentials:'same-origin'}).then(function(r){return r.status===200?r.json():null;})
.then(function(d){if(d&&open)render(d);if(open)loop();else polling=false;})
.catch(function(){if(open)setTimeout(loop,2000);else polling=false;});
})();}
})();
</script></body></html>"""
}