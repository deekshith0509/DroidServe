package com.deekshith.droidserve.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Launches VLC (or a fallback player) with a generated M3U so per-channel headers (UA/Referer)
 * survive the handoff. Single channel → 1-entry m3u; "play all" → full export.
 */
object VlcLauncher {

    fun isInstalled(context: Context): Boolean =
        try { context.packageManager.getPackageInfo("org.videolan.vlc", 0); true }
        catch (_: Exception) { false }

    /** Returns true if an external player was started (or the OS chooser shown), false if nothing could handle it. */
    fun launch(context: Context, channels: List<StreamLink>): Boolean {
        if (channels.isEmpty()) return false
        val dir = File(context.cacheDir, "vlc").apply { mkdirs() }
        val file = File(dir, "playlist_${System.currentTimeMillis()}.m3u")
        file.writeText(TvPlaylist.export(channels))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "audio/x-mpegurl")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val pkgs = listOf("org.videolan.vlc", "com.mxtech.videoplayer.pro", "com.mxtech.videoplayer.ad")
        val target = pkgs.firstOrNull { pkg ->
            try { context.packageManager.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
        }
        if (target != null) intent.setPackage(target)
        return try {
            context.startActivity(intent); true
        } catch (_: Exception) {
            try {
                context.startActivity(Intent.createChooser(intent, "Open with")); true
            } catch (_: Exception) { false }
        }
    }
}