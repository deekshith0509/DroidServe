package com.deekshith.droidserve

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.StatFs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gathers device / app / storage facts for the in-app transparency dashboard and the
 * web info page. Everything here is the device owner's own info, surfaced to them.
 */
object Diagnostics {

    fun device(): List<Pair<String, String>> = listOf(
        "Model"       to "${Build.MANUFACTURER} ${Build.MODEL}",
        "Device"      to Build.DEVICE,
        "Android"     to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "ABIs"        to Build.SUPPORTED_ABIS.joinToString(", "),
        "Board"       to Build.BOARD,
        "Hardware"    to Build.HARDWARE,
        "Build ID"    to Build.ID,
        "Kernel"      to (System.getProperty("os.version") ?: "?"),
        "Fingerprint" to Build.FINGERPRINT
    )

    fun app(context: Context): List<Pair<String, String>> {
        val rt = Runtime.getRuntime()
        val pi = try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (_: Exception) { null }
        return listOf(
            "Package"   to context.packageName,
            "Version"   to (pi?.versionName ?: "?"),
            "PID"       to Process.myPid().toString(),
            "CPU cores" to rt.availableProcessors().toString(),
            "Heap used" to FileUtils.formatSize(rt.totalMemory() - rt.freeMemory()),
            "Heap max"  to FileUtils.formatSize(rt.maxMemory())
        )
    }

    fun storage(context: Context): List<Pair<String, String>> = try {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val st = StatFs(dir.absolutePath)
        val total = st.blockCountLong * st.blockSizeLong
        val free  = st.availableBlocksLong * st.blockSizeLong
        listOf(
            "Volume" to dir.absolutePath,
            "Total"  to FileUtils.formatSize(total),
            "Free"   to FileUtils.formatSize(free),
            "Used"   to FileUtils.formatSize(total - free)
        )
    } catch (_: Exception) { listOf("Storage" to "unavailable") }

    fun formatUptime(ms: Long): String {
        if (ms <= 0) return "—"
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return buildString {
            if (h > 0) append("${h}h ")
            if (h > 0 || m > 0) append("${m}m ")
            append("${sec}s")
        }
    }

    private val TIME_FMT = SimpleDateFormat("HH:mm:ss", Locale.US)
    fun clock(t: Long): String = TIME_FMT.format(Date(t))
}
