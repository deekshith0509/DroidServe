package com.deekshith.droidserve.tv

/** A DroidServe phone server discovered on the LAN. */
data class DiscoveredServer(
    val name: String,
    val host: String,
    val port: Int
) {
    val baseUrl: String get() = "http://$host:$port"
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
    val url: String
) {
    val isVideo: Boolean get() = mime.startsWith("video/")
    val isAudio: Boolean get() = mime.startsWith("audio/")
    val isImage: Boolean get() = mime.startsWith("image/")
    val isText: Boolean get() = mime.startsWith("text/") ||
        mime == "application/json" || mime == "application/xml" || mime == "application/javascript"
    val isPlayable: Boolean get() = isVideo || isAudio
}

data class Listing(
    val path: String,
    val entries: List<RemoteEntry>
)

/** A real-time command pushed from the phone (controller) to this TV (player). */
data class CastCommand(
    val action: String,
    val url: String,
    val mime: String
)
