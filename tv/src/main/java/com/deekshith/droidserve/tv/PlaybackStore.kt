package com.deekshith.droidserve.tv

import android.content.Context
import java.io.File

/**
 * Remembers where each video was last stopped so it can resume on the next play. Positions are
 * keyed by the file's URL (token query stripped, so a new session token doesn't lose the mark).
 *
 * Cache hygiene: the whole store is dropped on app start if it has grown past [MAX_BYTES] (100 KB),
 * so stale resume marks from long-gone files can never accumulate unbounded on a low-storage TV.
 */
class PlaybackStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS = "droidserve_tv_playback"
        private const val MAX_BYTES = 100 * 1024L   // 100 KB

        /**
         * Call once at app startup. If the backing prefs file exceeds 100 KB, clear it — bounded
         * cleanup so the resume cache can't grow forever across many sessions.
         */
        fun pruneIfLarge(context: Context) {
            try {
                val app = context.applicationContext
                val file = File(File(app.filesDir.parentFile, "shared_prefs"), "$PREFS.xml")
                if (file.exists() && file.length() > MAX_BYTES) {
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
                    file.delete()
                }
            } catch (_: Exception) {}
        }
    }

    // Strip the "?tok=…" (or any query) so the key is stable even when the server hands out a new
    // session token on restart.
    private fun key(url: String): String = url.substringBefore('?')

    /** Save a resume position. Positions under 3s are treated as "start over" and cleared. */
    fun save(url: String, positionMs: Int) {
        if (positionMs < 3_000) { clear(url); return }
        prefs.edit().putInt(key(url), positionMs).apply()
    }

    /** Last saved position in ms for this video, or 0 if none. */
    fun get(url: String): Int = prefs.getInt(key(url), 0)

    fun clear(url: String) { prefs.edit().remove(key(url)).apply() }
}
