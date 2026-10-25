package com.deekshith.droidserve

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import java.io.File

/**
 * Share-sheet target: "Share → DroidServe" from any app (YouTube, a browser, a Telegram link, …)
 * sends a URL straight to the TV links list — no need to open the web UI. The pasted/shared text is
 * scanned for a URL, resolved to its direct stream (same path as the web UI's Send-to-TV), added to
 * [LinkStore], then the activity finishes. Runs in its own translucent, recents-excluded task.
 */
class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: intent?.dataString
        val url = raw?.let { URL_RE.find(it)?.value }
        if (url == null) {
            Toast.makeText(this, "No link found to send", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        runCatching { LinkStore.attach(File(filesDir, "links.tsv")) }
        Toast.makeText(this, "Sending to TV…", Toast.LENGTH_SHORT).show()
        Thread {
            val res = LinkIngest.ingest(url)
            runOnUiThread {
                val msg = when {
                    res.group != null -> "Sent ${res.added} channels to TV ✓"
                    res.playable      -> "Sent to TV ✓"
                    else              -> "Added to TV links"
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                finish()
            }
        }.start()
    }

    private companion object {
        val URL_RE = Regex("""(https?://|magnet:)\S+""", RegexOption.IGNORE_CASE)
    }
}
