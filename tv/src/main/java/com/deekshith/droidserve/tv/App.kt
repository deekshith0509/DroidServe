package com.deekshith.droidserve.tv

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * App entry point. Installs a global uncaught-exception handler that persists the full stack
 * trace to a file, so a crash on a device we can't attach adb to (e.g. an Android 5.0 TV) can
 * still be read back on-screen by MainActivity. Catches Errors too (VerifyError,
 * NoClassDefFoundError, etc.) which ordinary try/catch blocks miss.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                crashFile(this).writeText(
                    "Thread: ${thread.name}\n" +
                        "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
                        "Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n\n" +
                        sw.toString()
                )
            } catch (_: Throwable) {}
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        fun crashFile(app: Application): File = File(app.filesDir, "last_crash.txt")
    }
}
