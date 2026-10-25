package com.deekshith.droidserve.tv.ui

import androidx.compose.ui.graphics.vector.ImageVector
import com.deekshith.droidserve.tv.RemoteEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pure formatting + mapping helpers shared by the screens. Kept Compose-free so they unit-test. */

fun breadcrumb(path: String): String =
    path.split("/").filter { it.isNotEmpty() }.joinToString("   ›   ")

fun iconFor(e: RemoteEntry): ImageVector = when {
    e.isDir -> DsIcons.Folder
    e.isVideo -> DsIcons.Video
    e.isAudio -> DsIcons.Audio
    e.isImage -> DsIcons.Image
    e.isApk -> DsIcons.Apk
    else -> DsIcons.Doc
}

/** Row metadata: a single value (size, or "Folder"). The CC/subtitle flag is shown as its own mark. */
fun metaFor(e: RemoteEntry): String = if (e.isDir) "Folder" else formatSize(e.size)

enum class LinkKind { MAGNET, AUDIO, VIDEO }

fun linkKind(u: String): LinkKind = when {
    u.startsWith("magnet:", ignoreCase = true) -> LinkKind.MAGNET
    Regex("\\.(mp3|flac|aac|ogg|wav|m4a|opus)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(u) -> LinkKind.AUDIO
    else -> LinkKind.VIDEO
}

fun iconForLink(u: String): ImageVector = when (linkKind(u)) {
    LinkKind.MAGNET -> DsIcons.Magnet
    LinkKind.AUDIO -> DsIcons.Audio
    LinkKind.VIDEO -> DsIcons.Video
}

private val DATE_FMT = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
fun formatDate(ms: Long): String = if (ms <= 0L) "" else DATE_FMT.format(Date(ms))

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1_048_576 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1_073_741_824 -> "%.1f MB".format(bytes / 1_048_576.0)
    else -> "%.2f GB".format(bytes / 1_073_741_824.0)
}

fun fmtTime(ms: Int): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** Power-of-two downsample so a decoded image lands near [target] px on its longest edge. */
fun sampleSize(w: Int, h: Int, target: Int): Int {
    if (w <= 0 || h <= 0) return 1
    var s = 1
    while (w / (s * 2) >= target || h / (s * 2) >= target) s *= 2
    return s
}
