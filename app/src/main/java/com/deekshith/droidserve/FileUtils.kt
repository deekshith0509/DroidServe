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
        "apk"  to "application/vnd.android.package-archive",
        "wasm" to "application/wasm",
        "ttf"  to "font/ttf",
        "woff" to "font/woff",
        "woff2" to "font/woff2"
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
        if (allowZip) {
            val here = encodePath(urlPath)
            val zipHref = if (here.isEmpty()) "/?zip=1" else "/$here?zip=1"
            sb.append("""<a class="sb zipall" href="""").append(zipHref).append(tokAmp)
            sb.append("""">⬇ Folder ZIP</a>""")
        }
        sb.append("""<button class="sb on" data-s="d">Default</button>""")
        sb.append("""<button class="sb" data-s="n">Name</button>""")
        sb.append("""<button class="sb" data-s="s">Size ↓</button>""")
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
        for (e in entries) {
            val eName = e.name
            val enc   = encodeSeg(eName)
            val href  = if (base.isEmpty()) enc else "$base/$enc"

            if (e.isDirectory) {
                sb.append("""<div class="item dir" data-name="${escHtml(eName)}" data-size="-1">""")
                sb.append("""<a class="item-main" href="/""").append(href).append("""">""")
                sb.append("""<span class="icon">📁</span>""")
                sb.append("""<div class="info"><div class="name">""").append(escHtml(eName))
                sb.append("""</div><div class="meta">Directory</div></div></a>""")
                if (allowZip) {
                    sb.append("""<a class="btn-zip" href="/""").append(href).append("""?zip=1""").append(tokAmp).append(""">⬇ ZIP</a>""")
                }
                sb.append("</div>")
            } else {
                val date = formatDate(e.lastModified)
                val meta = if (date.isEmpty()) formatSize(e.size) else "${formatSize(e.size)} · $date"
                val mime = getMimeType(eName)
                // Every file carries its mime so client JS can offer "open with" (Android chooser).
                sb.append("""<div class="item file" data-name="${escHtml(eName)}" data-size="${e.size}" data-mime="${escHtml(mime)}">""")
                // Tapping the name opens the built-in player/viewer page, which streams
                // inline where possible and always offers "Open in VLC" + direct + download.
                sb.append("""<a class="item-main" href="/""").append(href).append("""?play=1""").append(tokAmp).append("""">""")
                sb.append("""<span class="icon">""").append(fileIcon(eName)).append("</span>")
                sb.append("""<div class="info"><div class="name">""").append(escHtml(eName))
                sb.append("""</div><div class="meta">""").append(escHtml(meta))
                sb.append("</div></div></a>")
                // Download button pinned to the right edge of the row. Always shown when
                // downloads are allowed; it's the explicit "save file" action, separate from
                // tapping the name (which opens the file / offers an app chooser).
                if (allowDownload) {
                    sb.append("""<a class="btn-dl" href="/""").append(href)
                    sb.append("""?dl=1""").append(tokAmp).append("""" download="${escHtml(eName)}" title="Download">⬇</a>""")
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
    // Player / viewer page — one universal page that plays or shows any file, with
    // guaranteed cross-platform fallbacks (in-browser player, Open in VLC, download).
    // ----------------------------------------------------------------------
    fun buildPlayerPage(
        fileName: String,
        urlPath: String,
        mime: String,
        allowDownload: Boolean,
        title: String = "DroidServe",
        authToken: String? = null
    ): String {
        val safeTitle = escHtml(title.ifBlank { "DroidServe" })
        val safeName  = escHtml(fileName)
        val encPath   = urlPath.split('/').filter { it.isNotEmpty() }.joinToString("/") { encodeSeg(it) }
        val tokQ  = if (authToken != null) "?tok=${encodeSeg(authToken)}" else ""
        val tokAmp = if (authToken != null) "&tok=${encodeSeg(authToken)}" else ""
        val src   = "/$encPath$tokQ"                       // raw stream URL
        val dlUrl = "/$encPath?dl=1$tokAmp"                // forced download
        val m3u   = "/$encPath?m3u=1$tokAmp"               // playlist for VLC/MX
        // Parent folder to return to (carry token so the listing stays authorized)
        val parentPath = urlPath.trimStart('/').substringBeforeLast('/', "")
            .split('/').filter { it.isNotEmpty() }.joinToString("/") { encodeSeg(it) }
        val backUrl = "/$parentPath" + (if (authToken != null) "?tok=${encodeSeg(authToken)}" else "")

        // Categorise. "Browser can play" = formats mainstream browsers decode natively.
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val browserVideo = ext in setOf("mp4", "webm", "ogv", "m4v", "mov")
        val browserAudio = ext in setOf("mp3", "ogg", "oga", "wav", "flac", "aac", "m4a", "opus", "weba")
        val isVideo = mime.startsWith("video/")
        val isAudio = mime.startsWith("audio/")
        val isImage = mime.startsWith("image/")
        val isPdf   = mime == "application/pdf"
        val isText  = mime.startsWith("text/") || mime == "application/json" || mime == "application/xml"

        val sb = StringBuilder(4096)
        sb.append(PLAYER_HEAD_1).append(safeName).append(" — ").append(safeTitle).append(PLAYER_HEAD_2)
        sb.append("""<a class="back" href="""").append(backUrl).append("""">← Back</a>""")
        sb.append("""<div class="pname">""").append(iconFor(fileName)).append(' ').append(safeName).append("</div>")

        sb.append("""<div class="stage">""")
        when {
            isVideo -> {
                sb.append("""<video id="pl" controls autoplay playsinline preload="metadata" src="""").append(src).append("""" poster=""></video>""")
                if (!browserVideo) sb.append(unsupportedNote("This video format may not play in-browser."))
            }
            isAudio -> {
                sb.append("""<div class="audiowrap"><div class="acover">🎵</div><audio id="pl" controls autoplay preload="metadata" src="""").append(src).append(""""></audio></div>""")
                if (!browserAudio) sb.append(unsupportedNote("This audio format may not play in-browser."))
            }
            isImage -> sb.append("""<img class="pimg" src="""").append(src).append("""" alt="""").append(safeName).append("""">""")
            isPdf   -> sb.append("""<iframe class="pframe" src="""").append(src).append(""""></iframe>""")
            isText  -> sb.append("""<iframe class="pframe ptext" src="""").append(src).append(""""></iframe>""")
            else    -> sb.append("""<div class="unknown"><div class="ubig">📄</div><div>No in-browser preview for this file type.</div><div class="usub">Use the buttons below to open or download it.</div></div>""")
        }
        sb.append("</div>")   // .stage

        // Action buttons — universal fallbacks that work on every platform.
        sb.append("""<div class="acts">""")
        if (isVideo || isAudio) {
            // "Open in VLC" via m3u works on laptop + phone + TV, even for mkv/avi.
            sb.append("""<a class="act primary" href="""").append(m3u).append("""">▶ Open in VLC / player</a>""")
        }
        sb.append("""<a class="act" href="""").append(src).append("""" target="_blank" rel="noopener">🔗 Open raw stream</a>""")
        if (allowDownload) sb.append("""<a class="act" href="""").append(dlUrl).append("""" download="""").append(safeName).append("""">⬇ Download</a>""")
        // Android-only: hand to the system app chooser directly.
        sb.append("""<button class="act" id="openWith" style="display:none">📱 Open with app…</button>""")
        sb.append("</div>")

        sb.append(PLAYER_TAIL_1)
        // Pass data to the small script.
        sb.append("""<script>window.DS={src:"""").append(jsStr(src)).append(""",mime:""").append(jsStr(mime)).append("""};</script>""")
        sb.append(PLAYER_TAIL_2)
        return sb.toString()
    }

    private fun unsupportedNote(msg: String): String =
        """<div class="warn">⚠ ${escHtml(msg)} Tap <b>Open in VLC / player</b> below to stream it in a real player.</div>"""

    private fun iconFor(name: String): String = fileIcon(name)

    // Minimal JS string escaper for embedding server values safely.
    private fun jsStr(s: String): String {
        val out = StringBuilder(s.length + 2).append('"')
        for (c in s) when (c) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '<' -> out.append("\\u003c")
            '>' -> out.append("\\u003e")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            else -> out.append(c)
        }
        return out.append('"').toString()
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
.btn-zip,.btn-dl{flex-shrink:0;display:flex;align-items:center;justify-content:center;padding:0 14px;font-weight:700;text-decoration:none;border-left:1px solid var(--bd);white-space:nowrap;cursor:pointer;transition:filter .12s}
.btn-zip{background:var(--gz);color:#fff;min-width:66px;font-size:12px}
.btn-zip:hover{filter:brightness(1.15)}
.btn-dl{background:var(--dl);color:#fff;min-width:48px;font-size:18px}
.btn-dl:hover{filter:brightness(1.15)}
.empty{text-align:center;color:var(--mu);padding:60px 20px;font-size:14px}
.info-foot{margin-top:16px;padding:14px;border:1px solid var(--bd);border-radius:11px;background:var(--sf);font-size:11px;color:var(--mu);line-height:1.7;word-break:break-all;box-shadow:var(--sh)}
.info-h{color:var(--ac);font-weight:700;margin-bottom:6px;font-size:12px}
::-webkit-scrollbar{width:6px}::-webkit-scrollbar-track{background:var(--bg)}::-webkit-scrollbar-thumb{background:var(--bd);border-radius:3px}
</style></head>
<body>
<header>
<div class="hr"><h1>📡 """

    private val HTML_HEAD_2B = """</h1><button class="tgl" id="tg" title="Toggle theme" aria-label="Toggle theme">🌙</button></div>
<input type="search" id="q" placeholder="Filter files in this folder…" autocomplete="off" autocorrect="off" spellcheck="false">
<nav class="crumb">"""

    private val HTML_HEAD_3 = """</nav>
</header>
<main>"""

    private val HTML_TAIL = """</main>
<script>
(function(){
// Theme toggle — persisted in localStorage, defaults to dark.
var root=document.documentElement,tg=document.getElementById('tg');
function applyTheme(t){root.setAttribute('data-t',t);tg.textContent=t==='light'?'☀️':'🌙';}
applyTheme(localStorage.getItem('ds-theme')||'dark');
tg.addEventListener('click',function(){var t=root.getAttribute('data-t')==='light'?'dark':'light';localStorage.setItem('ds-theme',t);applyTheme(t);});

var ls=document.getElementById('ls'),st=document.getElementById('st'),em=document.getElementById('em'),q=document.getElementById('q');
var all=Array.from(ls.querySelectorAll('.item[data-name]')),orig=all.slice(),T=all.length;
function upd(){var v=0,s=q.value.toLowerCase();all.forEach(function(e){var ok=!s||e.dataset.name.toLowerCase().indexOf(s)>=0;e.style.display=ok?'':'none';if(ok)v++;});st.textContent=v+(v!==T?' of '+T:'')+' item'+(T!==1?'s':'');em.style.display=v===0&&T>0?'':'none';}
upd();
var t;q.addEventListener('input',function(){clearTimeout(t);t=setTimeout(upd,60);});
// '/' focuses the filter box for quick keyboard search
document.addEventListener('keydown',function(e){if(e.key==='/'&&document.activeElement!==q){e.preventDefault();q.focus();}});
document.querySelectorAll('.sb[data-s]').forEach(function(b){
b.addEventListener('click',function(){
document.querySelectorAll('.sb[data-s]').forEach(function(x){x.classList.remove('on');});b.classList.add('on');
var m=b.dataset.s,arr;
// Folders always stay grouped before files; the chosen order applies within each group.
function grp(e){return e.classList.contains('dir')?0:1;}
if(m==='d')arr=orig.slice();
else if(m==='n')arr=all.slice().sort(function(a,b){return grp(a)-grp(b)||a.dataset.name.localeCompare(b.dataset.name,undefined,{sensitivity:'base'});});
else arr=all.slice().sort(function(a,b){return grp(a)-grp(b)||Number(b.dataset.size||0)-Number(a.dataset.size||0);});
arr.forEach(function(e){ls.appendChild(e);});
});});

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
</script></body></html>"""

    // ----------------------------------------------------------------------
    // Player / viewer page template chunks
    // ----------------------------------------------------------------------
    private val PLAYER_HEAD_1 = """<!DOCTYPE html>
<html lang="en"><head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>"""

    private val PLAYER_HEAD_2 = """</title>
<style>
*{box-sizing:border-box;margin:0;padding:0;-webkit-tap-highlight-color:transparent}
:root{--bg:#0f172a;--sf:#1e293b;--bd:#334155;--ac:#38bdf8;--tx:#e2e8f0;--mu:#7c8aa0;--dl:#10b981;--gz:#0ea5e9}
html[data-t=light]{--bg:#f1f5f9;--sf:#fff;--bd:#dbe2ea;--ac:#0284c7;--tx:#0f172a;--mu:#64748b;--dl:#059669;--gz:#0284c7}
body{font-family:system-ui,-apple-system,sans-serif;background:var(--bg);color:var(--tx);min-height:100vh;display:flex;flex-direction:column;padding:12px;padding-bottom:env(safe-area-inset-bottom,12px)}
.back{color:var(--ac);text-decoration:none;font-size:14px;font-weight:600;display:inline-block;padding:6px 2px}
.back:hover{text-decoration:underline}
.pname{font-size:15px;font-weight:600;margin:6px 0 12px;word-break:break-all;line-height:1.4}
.stage{flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:40vh;background:#000;border:1px solid var(--bd);border-radius:14px;overflow:hidden;padding:0}
html[data-t=light] .stage{background:#000}
video{width:100%;max-height:78vh;background:#000;display:block}
.audiowrap{width:100%;padding:32px 16px;display:flex;flex-direction:column;align-items:center;gap:20px;background:var(--sf)}
.acover{font-size:72px}
audio{width:100%;max-width:520px}
.pimg{max-width:100%;max-height:80vh;object-fit:contain;display:block}
.pframe{width:100%;height:80vh;border:0;background:#fff}
.ptext{background:#fff}
.unknown{padding:48px 20px;text-align:center;color:var(--mu);background:var(--sf);width:100%}
.ubig{font-size:64px;margin-bottom:12px}
.usub{font-size:12px;margin-top:6px}
.warn{width:100%;background:#7c2d12;color:#fed7aa;padding:10px 14px;font-size:13px;line-height:1.5}
html[data-t=light] .warn{background:#fff7ed;color:#9a3412}
.acts{display:flex;flex-wrap:wrap;gap:10px;margin-top:16px}
.act{flex:1;min-width:140px;text-align:center;background:var(--sf);border:1px solid var(--bd);color:var(--tx);border-radius:11px;padding:14px 16px;font-size:14px;font-weight:600;text-decoration:none;cursor:pointer;transition:border-color .12s,background .12s}
.act:hover,.act:focus-visible{border-color:var(--ac);outline:none;box-shadow:0 0 0 3px var(--ac)}
.act.primary{background:var(--gz);color:#fff;border-color:var(--gz)}
.act.primary:hover{filter:brightness(1.12)}
</style></head>
<body>"""

    private val PLAYER_TAIL_1 = ""

    private val PLAYER_TAIL_2 = """
<script>
(function(){
// Inherit the theme chosen on the listing page.
document.documentElement.setAttribute('data-t',localStorage.getItem('ds-theme')||'dark');
var ua=navigator.userAgent,isAndroid=/Android/i.test(ua);
// On Android, expose "Open with app…" which hands the stream URL to the system chooser
// (VLC / MX Player / any handler) via an intent:// URL.
if(isAndroid){
var b=document.getElementById('openWith');
if(b){b.style.display='';
b.addEventListener('click',function(){
var a=document.createElement('a');a.href=DS.src;var abs=a.href;
var m=abs.match(/^(https?):\/\/([^#]*)$/i);if(!m){window.location.href=abs;return;}
var it='intent://'+m[2]+'#Intent;scheme='+m[1]+';action=android.intent.action.VIEW';
if(DS.mime)it+=';type='+DS.mime;it+=';end';
window.location.href=it;
});}
}
// If the in-browser player errors (codec unsupported), nudge the user toward the player button.
var pl=document.getElementById('pl');
if(pl){pl.addEventListener('error',function(){
var w=document.querySelector('.warn');
if(!w){w=document.createElement('div');w.className='warn';w.innerHTML='⚠ This file can\u2019t play in this browser. Use <b>Open in VLC / player</b> below.';document.querySelector('.stage').appendChild(w);}
});}
})();
</script></body></html>"""
}