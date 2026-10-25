package com.deekshith.droidserve

/**
 * One smart entry point for a pasted/shared URL: fetch + classify once, then dispatch.
 *
 * A direct stream, HLS manifest, or watch page becomes a single link; a multi-channel playlist
 * (IPTV `.m3u`) auto-expands into one named group of channels. This is what lets a user paste any
 * link without first deciding "single link vs. playlist import" — the classification is automatic
 * (see [StreamResolver.classify] / [LinkClassifier]). Shared by the web `add` route, the Share
 * sheet, and the in-app field so all three behave identically.
 */
object LinkIngest {

    data class Result(
        val version: Int,
        val links: List<LinkStore.Link>,
        val added: Int,
        /** Non-null when the URL expanded into a channel group. */
        val group: String?,
        val playable: Boolean
    )

    fun ingest(url: String, maxEntries: Int = Int.MAX_VALUE): Result =   // no cap; in-app card + share sheet use this
        when (val o = StreamResolver.classify(url, maxEntries)) {
            is StreamResolver.Outcome.Single -> {
                val (v, list) = LinkStore.add(o.url, o.title, o.playable)
                Result(v, list, 1, null, o.playable)
            }
            is StreamResolver.Outcome.Collection -> {
                val group = groupNameFor(url)
                val (v, list) = LinkStore.addBatch(o.entries.map { it.url to it.title }, group)
                Result(v, list, o.entries.size, group, true)
            }
        }

    /** Short display name for an auto-expanded playlist group: its file base name, else host. */
    internal fun groupNameFor(url: String): String {
        val afterScheme = url.substringAfter("://", url).substringBefore('#').substringBefore('?')
        val host = afterScheme.substringBefore('/')
        val lastSeg = afterScheme.substringAfter('/', "").trimEnd('/').substringAfterLast('/')
        val base = if (lastSeg.isNotBlank()) lastSeg.substringBeforeLast('.', lastSeg) else host
        return base.ifBlank { "Playlist" }.take(40)
    }
}
