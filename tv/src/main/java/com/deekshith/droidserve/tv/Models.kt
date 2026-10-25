package com.deekshith.droidserve.tv

/** A DroidServe phone server discovered on the LAN. */
data class DiscoveredServer(
    val name: String,
    val host: String,
    val port: Int,
    val device: String = ""
) {
    val baseUrl: String get() = "http://$host:$port"
    val displayName: String get() = device.ifEmpty { name }
}

/** Result of GET /api/info. */
data class ServerInfo(
    val name: String,
    val device: String,
    val auth: Boolean,
    val tokenQuery: String,
    val apiVersion: Int
)

/** One row in a directory listing. */
data class RemoteEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val mime: String,
    /** Absolute-from-root href (already token-suffixed) for fetching this resource. */
    val url: String,
    /** Absolute cached-thumbnail URL (/thumb/…) for image/video rows, or null. */
    val thumbUrl: String? = null,
    /** Absolute subtitle URL (WebVTT) for a video, or null. Handed to the external player. */
    val subUrl: String? = null
) {
    val isVideo: Boolean get() = mime.startsWith("video/")
    val isAudio: Boolean get() = mime.startsWith("audio/")
    val isImage: Boolean get() = mime.startsWith("image/")
    // Text-ish types that render fine in the in-app viewer. Includes common non-"text/*" MIME
    // types the server assigns (JSON/XML/JS) and subtitle files (.srt = application/x-subrip,
    // .vtt = text/vtt) — otherwise clicking a subtitle would be punted to an external player,
    // which on some TVs has no text handler (or a buggy one that crashes).
    val isText: Boolean get() = mime.startsWith("text/") ||
        mime == "application/json" || mime == "application/xml" || mime == "application/javascript" ||
        mime == "application/x-subrip" || mime == "application/x-subtitle" || mime == "application/octet-stream" &&
        name.substringAfterLast('.', "").lowercase() in TEXTISH_EXTS
    val isPlayable: Boolean get() = isVideo || isAudio
    val isThumbnailable: Boolean get() = isImage || isVideo
    val isApk: Boolean get() = mime == "application/vnd.android.package-archive" || name.endsWith(".apk", ignoreCase = true)
}

// Extensionless or octet-stream files that are really plain text (the web UI treats these inline
// too). Used as a fallback when the MIME type is uninformative.
private val TEXTISH_EXTS = setOf(
    "srt","vtt","txt","md","log","csv","ini","conf","cfg","json","xml","yaml","yml","toml",
    "kt","java","py","js","ts","c","h","cpp","sh","gradle","properties"
)

data class Listing(
    val path: String,
    val entries: List<RemoteEntry>
)

/** One folder-grouped album from /api/music. [cover] and song URLs are absolute (token-carrying). */
data class MusicAlbum(
    val path: String,
    val name: String,
    val cover: String,
    val songs: List<RemoteEntry>
)

/** One external streaming link the phone pushed to this TV (opened on demand in a native player). */
data class StreamLink(
    val id: String,
    val url: String,
    val label: String,
    val addedAt: Long,
    /** True = a direct stream (open in a video player); false = a web page (open in a browser). */
    val playable: Boolean = true,
    /** Non-null when this link is one channel of an auto-expanded playlist group. */
    val group: String? = null
)

/** A real-time command pushed from the phone (controller) to this TV (player). */
data class CastCommand(
    val action: String,
    val url: String,
    val mime: String,
    val subUrl: String? = null
)
