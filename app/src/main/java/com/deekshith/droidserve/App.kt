package com.deekshith.droidserve

import android.app.Application
import java.io.File

/**
 * Attaches [LinkStore] to its backing file at process start, so links added from the in-app field
 * or the Share sheet persist even before the HTTP server has been started for the first time.
 * (The server also attaches on start; [LinkStore.attach] is idempotent.)
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { LinkStore.attach(File(filesDir, "links.tsv")) }
    }
}
