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

    fun getMimeType(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        if (dot < 0) return "application/octet-stream"
        val ext = fileName.substring(dot + 1).lowercase()
        return MIME_MAP[ext]
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
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
        out: OutputStream
    ) {
        val buf = ByteArray(BUFFER_SIZE)
        val zos = ZipOutputStream(out.buffered(BUFFER_SIZE))
        try {
            for (e in entries) writeEntry(context, e, rootName, zos, buf)
            zos.finish()
            zos.flush()
        } finally {
            try { zos.close() } catch (_: Exception) {}
        }
    }

    private fun writeEntry(
        context: Context, e: FileEntry, path: String,
        zos: ZipOutputStream, buf: ByteArray
    ) {
        val fullPath = "$path/${e.name}"
        if (e.isDirectory) {
            zos.putNextEntry(ZipEntry("$fullPath/")); zos.closeEntry()
        } else {
            zos.putNextEntry(ZipEntry(fullPath).also { it.time = e.lastModified })
            var stream: InputStream? = null
            try {
                stream = context.contentResolver.openInputStream(e.uri)
                stream?.let { inp ->
                    var n: Int
                    while (inp.read(buf).also { n = it } != -1) zos.write(buf, 0, n)
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

    fun buildHtml(entries: List<FileEntry>, urlPath: String, dirName: String): String {
        val sb = StringBuilder(4096 + entries.size * 256)

        sb.append(HTML_HEAD_1)
        sb.append(escHtml(dirName))
        sb.append(HTML_HEAD_2)
        sb.append(escHtml(urlPath.trimStart('/')))
        sb.append(HTML_HEAD_3)

        // Parent link
        if (urlPath.isNotEmpty() && urlPath != "/") {
            val parent = urlPath.trimEnd('/').substringBeforeLast('/', "")
            sb.append("""<div class="item dir" data-name=".." data-size="-1"><a class="item-main" href="/""")
            sb.append(parent)
            sb.append(""""><span class="icon">⬆️</span><div class="info"><div class="name">..</div><div class="meta">Parent</div></div></a></div>""")
        }

        for (e in entries) {
            val eName = e.name
            val enc   = encodeSeg(eName)
            val href  = if (urlPath.isBlank() || urlPath == "/") enc else "$urlPath/$enc"

            if (e.isDirectory) {
                sb.append("""<div class="item dir" data-name="${escHtml(eName)}" data-size="-1">""")
                sb.append("""<a class="item-main" href="/""").append(href).append("""">""")
                sb.append("""<span class="icon">📁</span>""")
                sb.append("""<div class="info"><div class="name">""").append(escHtml(eName))
                sb.append("""</div><div class="meta">Directory</div></div></a>""")
                sb.append("""<a class="btn-zip" href="/""").append(href).append("""?zip=1">⬇ ZIP</a>""")
                sb.append("</div>")
            } else {
                val sz = formatSize(e.size)
                sb.append("""<div class="item file" data-name="${escHtml(eName)}" data-size="${e.size}">""")
                sb.append("""<a class="item-main" href="/""").append(href).append("""">""")
                sb.append("""<span class="icon">""").append(fileIcon(eName)).append("</span>")
                sb.append("""<div class="info"><div class="name">""").append(escHtml(eName))
                sb.append("""</div><div class="meta">""").append(escHtml(sz))
                sb.append("</div></div></a>")
                sb.append("""<a class="btn-dl" href="/""").append(href)
                sb.append("""?dl=1" download="${escHtml(eName)}" title="Download">⬇</a>""")
                sb.append("</div>")
            }
        }

        sb.append(HTML_TAIL)
        return sb.toString()
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

    private fun encodeSeg(s: String): String {
        var first = -1
        for (i in s.indices) {
            val c = s[i]
            if (c == '%' || c == ' ' || c == '#' || c == '?' || c == '&' || c == '+') { first = i; break }
        }
        if (first < 0) return s
        val out = StringBuilder(s.length + 16).append(s, 0, first)
        for (i in first until s.length) {
            when (s[i]) {
                '%'  -> out.append("%25")
                ' '  -> out.append("%20")
                '#'  -> out.append("%23")
                '?'  -> out.append("%3F")
                '&'  -> out.append("%26")
                '+'  -> out.append("%2B")
                else -> out.append(s[i])
            }
        }
        return out.toString()
    }

    // ----------------------------------------------------------------------
    // HTML template split into static chunks — no per-request .replace()
    // ----------------------------------------------------------------------
    private val HTML_HEAD_1 = """<!DOCTYPE html>
<html lang="en"><head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>DroidServe — """

    private val HTML_HEAD_2 = """</title>
<style>
*{box-sizing:border-box;margin:0;padding:0;-webkit-tap-highlight-color:transparent}
:root{--bg:#0f172a;--sf:#1e293b;--bd:#334155;--ac:#38bdf8;--tx:#e2e8f0;--mu:#64748b;--gz:#0ea5e9;--dl:#10b981}
body{font-family:system-ui,-apple-system,sans-serif;background:var(--bg);color:var(--tx);min-height:100vh;padding-bottom:env(safe-area-inset-bottom,0)}
header{background:var(--sf);padding:12px 16px;border-bottom:1px solid var(--bd);position:sticky;top:0;z-index:100}
.hr{display:flex;align-items:center;gap:10px;margin-bottom:8px}
h1{font-size:18px;color:var(--ac);font-weight:800;flex:1}
input[type=search]{width:100%;padding:9px 14px;border-radius:10px;border:1.5px solid var(--bd);background:var(--bg);color:var(--tx);font-size:15px;outline:none;transition:border-color .15s}
input[type=search]:focus{border-color:var(--ac)}
.path{font-size:11px;color:var(--mu);margin-top:6px;word-break:break-all}
main{padding:12px;max-width:1200px;margin:0 auto}
.tb{display:flex;gap:6px;margin-bottom:8px;align-items:center;flex-wrap:wrap}
.st{font-size:11px;color:var(--mu);flex:1}
.sb{background:var(--sf);border:1px solid var(--bd);color:var(--tx);border-radius:6px;padding:5px 11px;font-size:11px;cursor:pointer;transition:border-color .12s,color .12s}
.sb:hover,.sb.on{border-color:var(--ac);color:var(--ac)}
.list{display:flex;flex-direction:column;gap:4px}
.item{display:flex;align-items:stretch;background:var(--sf);border:1px solid var(--bd);border-radius:10px;overflow:hidden;transition:border-color .12s,background .12s;min-height:54px}
.item:hover{border-color:var(--ac);background:#1e3a5f}
.item-main{flex:1;min-width:0;display:flex;align-items:center;gap:10px;padding:10px 12px;text-decoration:none;color:var(--tx)}
.icon{font-size:20px;min-width:24px;text-align:center;flex-shrink:0}
.info{flex:1;min-width:0}
.name{font-weight:500;font-size:14px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;line-height:1.3}
.meta{font-size:11px;color:var(--mu);margin-top:2px}
.btn-zip,.btn-dl{flex-shrink:0;display:flex;align-items:center;justify-content:center;padding:0 14px;font-weight:700;text-decoration:none;border-left:1px solid var(--bd);white-space:nowrap;cursor:pointer;transition:filter .12s}
.btn-zip{background:var(--gz);color:#fff;min-width:68px;font-size:12px}
.btn-zip:hover{filter:brightness(1.15)}
.btn-dl{background:var(--dl);color:#fff;min-width:46px;font-size:18px}
.btn-dl:hover{filter:brightness(1.15)}
.empty{text-align:center;color:var(--mu);padding:60px 20px;font-size:14px}
::-webkit-scrollbar{width:5px}::-webkit-scrollbar-track{background:var(--bg)}::-webkit-scrollbar-thumb{background:var(--bd);border-radius:3px}
</style></head>
<body>
<header>
<div class="hr"><h1>📡 DroidServe</h1></div>
<input type="search" id="q" placeholder="Filter files…" autocomplete="off" autocorrect="off" spellcheck="false">
<div class="path">/"""

    private val HTML_HEAD_3 = """</div>
</header>
<main>
<div class="tb"><span class="st" id="st"></span>
<button class="sb on" data-s="d">Default</button>
<button class="sb" data-s="n">Name</button>
<button class="sb" data-s="s">Size ↓</button>
</div>
<div class="list" id="ls">"""

    private val HTML_TAIL = """</div>
<div class="empty" id="em" style="display:none">No items match.</div>
</main>
<script>
(function(){
var ls=document.getElementById('ls'),st=document.getElementById('st'),em=document.getElementById('em'),q=document.getElementById('q');
var all=Array.from(ls.querySelectorAll('.item[data-name]')),orig=all.slice(),T=all.length;
function upd(){var v=0,s=q.value.toLowerCase();all.forEach(function(e){var ok=!s||e.dataset.name.toLowerCase().indexOf(s)>=0;e.style.display=ok?'':'none';if(ok)v++;});st.textContent=v+(v!==T?' of '+T:'')+' item'+(T!==1?'s':'');em.style.display=v===0&&T>0?'':'none';}
upd();
var t;q.addEventListener('input',function(){clearTimeout(t);t=setTimeout(upd,60);});
document.querySelectorAll('.sb').forEach(function(b){
b.addEventListener('click',function(){
document.querySelectorAll('.sb').forEach(function(x){x.classList.remove('on');});b.classList.add('on');
var m=b.dataset.s,arr;
if(m==='d')arr=orig.slice();
else if(m==='n')arr=all.slice().sort(function(a,b){return a.dataset.name.localeCompare(b.dataset.name,undefined,{sensitivity:'base'});});
else arr=all.slice().sort(function(a,b){return Number(b.dataset.size||0)-Number(a.dataset.size||0);});
arr.forEach(function(e){ls.appendChild(e);});
});});
})();
</script></body></html>"""
}