package com.deekshith.droidserve.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * TV-local persistence for the Home screen: playlists and channels the user saved from the Links
 * screen. Saved items are self-contained (external stream URLs), so Home plays them with no phone
 * connected. Follows the [PlaybackStore] pattern — a private SharedPreferences file — but stores a
 * single hand-rolled JSON document (org.json), since prefs can't hold structured values.
 */
class SavedStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("droidserve_tv_saved", Context.MODE_PRIVATE)

    private fun linkToJson(l: StreamLink) = JSONObject().apply {
        put("id", l.id); put("url", l.url); put("label", l.label)
        put("addedAt", l.addedAt); put("playable", l.playable)
        l.group?.let { put("group", it) }
    }

    private fun jsonToLink(o: JSONObject) = StreamLink(
        o.optString("id"), o.optString("url"), o.optString("label"),
        o.optLong("addedAt"), o.optBoolean("playable", true),
        o.optString("group", "").takeIf { it.isNotEmpty() },
    )

    private fun readRoot(): JSONObject =
        runCatching { JSONObject(prefs.getString("data", "") ?: "") }.getOrDefault(JSONObject())

    private fun writeRoot(root: JSONObject) { prefs.edit().putString("data", root.toString()).apply() }

    private fun channelsToJson(list: List<StreamLink>) = JSONArray().apply { list.forEach { put(linkToJson(it)) } }

    private fun playlistsToJson(list: List<SavedPlaylist>) = JSONArray().apply {
        list.forEach { p -> put(JSONObject().apply { put("name", p.name); put("channels", channelsToJson(p.channels)) }) }
    }

    fun playlists(): List<SavedPlaylist> {
        val arr = readRoot().optJSONArray("playlists") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val ch = o.optJSONArray("channels") ?: JSONArray()
            SavedPlaylist(o.optString("name"), (0 until ch.length()).map { jsonToLink(ch.getJSONObject(it)) })
        }
    }

    fun channels(): List<StreamLink> {
        val arr = readRoot().optJSONArray("channels") ?: return emptyList()
        return (0 until arr.length()).map { jsonToLink(arr.getJSONObject(it)) }
    }

    fun isPlaylistSaved(name: String): Boolean = playlists().any { it.name == name }
    fun isChannelSaved(id: String): Boolean = channels().any { it.id == id }

    /** Save newest-first; re-saving a name replaces it. No-op for an empty channel list. */
    fun savePlaylist(name: String, channels: List<StreamLink>) {
        if (channels.isEmpty()) return
        val list = listOf(SavedPlaylist(name, channels)) + playlists().filter { it.name != name }
        writeRoot(readRoot().apply { put("playlists", playlistsToJson(list)) })
    }

    fun saveChannel(link: StreamLink) {
        val list = listOf(link) + channels().filter { it.id != link.id }
        writeRoot(readRoot().apply { put("channels", channelsToJson(list)) })
    }

    fun removePlaylist(name: String) {
        writeRoot(readRoot().apply { put("playlists", playlistsToJson(playlists().filter { it.name != name })) })
    }

    fun removeChannel(id: String) {
        writeRoot(readRoot().apply { put("channels", channelsToJson(channels().filter { it.id != id })) })
    }
}
