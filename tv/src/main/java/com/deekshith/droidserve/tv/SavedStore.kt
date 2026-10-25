package com.deekshith.droidserve.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * TV-local persistence for the saved playlists + links shown on the main screen. Saved items are
 * self-contained (external stream URLs), so they play with no phone connected.
 *
 * Backed by a JSON file in filesDir (not SharedPreferences, which isn't safe for multi-MB blobs —
 * a full IPTV index saved as a playlist can be several MB). The parsed lists are held in memory and
 * read cheaply (the main screen rebuilds often); mutations snapshot and write the file on a
 * background thread so a large save never blocks the UI.
 */
class SavedStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, "saved_links.json")
    private val io = Executors.newSingleThreadExecutor()

    private val playlistList = ArrayList<SavedPlaylist>()
    private val channelList = ArrayList<StreamLink>()

    init {
        val root = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        root.optJSONArray("playlists")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ch = o.optJSONArray("channels") ?: JSONArray()
                playlistList.add(SavedPlaylist(o.optString("name"), (0 until ch.length()).map { jsonToLink(ch.getJSONObject(it)) }))
            }
        }
        root.optJSONArray("channels")?.let { arr ->
            for (i in 0 until arr.length()) channelList.add(jsonToLink(arr.getJSONObject(i)))
        }
    }

    private fun linkToJson(l: StreamLink) = JSONObject().apply {
        put("id", l.id); put("url", l.url); put("label", l.label)
        put("addedAt", l.addedAt); put("playable", l.playable)
        l.group?.let { put("group", it) }
        l.logo?.let { put("logo", it) }
    }

    private fun jsonToLink(o: JSONObject) = StreamLink(
        o.optString("id"), o.optString("url"), o.optString("label"),
        o.optLong("addedAt"), o.optBoolean("playable", true),
        o.optString("group", "").takeIf { it.isNotEmpty() },
        o.optString("logo", "").takeIf { it.isNotEmpty() },
    )

    fun playlists(): List<SavedPlaylist> = ArrayList(playlistList)
    fun channels(): List<StreamLink> = ArrayList(channelList)
    fun isPlaylistSaved(name: String): Boolean = playlistList.any { it.name == name }
    fun isChannelSaved(id: String): Boolean = channelList.any { it.id == id }

    /** Save newest-first; re-saving a name replaces it. No-op for an empty link list. */
    @Synchronized fun savePlaylist(name: String, channels: List<StreamLink>) {
        if (channels.isEmpty()) return
        playlistList.removeAll { it.name == name }
        playlistList.add(0, SavedPlaylist(name, channels))
        persist()
    }

    @Synchronized fun saveChannel(link: StreamLink) {
        channelList.removeAll { it.id == link.id }
        channelList.add(0, link)
        persist()
    }

    @Synchronized fun removePlaylist(name: String) {
        if (playlistList.removeAll { it.name == name }) persist()
    }

    @Synchronized fun removeChannel(id: String) {
        if (channelList.removeAll { it.id == id }) persist()
    }

    // Snapshot on the caller's thread (cheap ref copy), then serialize + write the file off-thread.
    private fun persist() {
        val pls = ArrayList(playlistList)
        val chs = ArrayList(channelList)
        io.execute {
            val json = JSONObject().apply {
                put("playlists", JSONArray().apply {
                    pls.forEach { p ->
                        put(JSONObject().apply {
                            put("name", p.name)
                            put("channels", JSONArray().apply { p.channels.forEach { put(linkToJson(it)) } })
                        })
                    }
                })
                put("channels", JSONArray().apply { chs.forEach { put(linkToJson(it)) } })
            }.toString()
            runCatching { file.writeText(json) }
        }
    }
}
