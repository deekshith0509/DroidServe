package com.deekshith.droidserve

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the persisted phone -> TV streaming-links list (LinkStore + links JSON). */
class LinkStoreTest {

    @After fun cleanup() { LinkStore.clear() }

    @Test fun addThenSnapshot_newestFirst() {
        LinkStore.clear()
        LinkStore.add("http://h/a.mp4")
        LinkStore.add("magnet:?xt=urn:btih:abc&dn=Big+Buck")
        val (v, list) = LinkStore.snapshot()
        assertEquals(2, list.size)
        assertTrue(v > 0)
        assertEquals("magnet:?xt=urn:btih:abc&dn=Big+Buck", list[0].url)   // newest first
    }

    @Test fun add_blankIgnored() {
        LinkStore.clear()
        val before = LinkStore.snapshot().first
        LinkStore.add("   ")
        assertEquals(before, LinkStore.snapshot().first)   // version unchanged
        assertTrue(LinkStore.snapshot().second.isEmpty())
    }

    @Test fun add_dedupMovesToTop() {
        LinkStore.clear()
        LinkStore.add("http://h/a.mp4")
        LinkStore.add("http://h/b.mp4")
        LinkStore.add("http://h/a.mp4")
        val list = LinkStore.snapshot().second
        assertEquals(2, list.size)
        assertEquals("http://h/a.mp4", list[0].url)
    }

    @Test fun remove() {
        LinkStore.clear()
        LinkStore.add("http://h/a.mp4")
        val id = LinkStore.snapshot().second[0].id
        LinkStore.remove(id)
        assertTrue(LinkStore.snapshot().second.isEmpty())
    }

    @Test fun awaitChange_returnsImmediatelyWhenBehind() {
        LinkStore.clear()
        LinkStore.add("http://h/a.mp4")
        val (v, _) = LinkStore.awaitChange(-1, 2000)   // caller is behind → no blocking
        assertTrue(v >= 1)
    }

    @Test fun awaitChange_wakesOnAdd() {
        LinkStore.clear()
        val cur = LinkStore.snapshot().first
        Thread { Thread.sleep(80); LinkStore.add("http://late/x.mkv") }.start()
        val start = System.currentTimeMillis()
        val (v, list) = LinkStore.awaitChange(cur, 3000)
        assertTrue(v > cur)
        assertEquals("http://late/x.mkv", list[0].url)
        assertTrue("should wake on add, not block full timeout", System.currentTimeMillis() - start < 2500)
    }

    @Test fun addBatch_addsAllItemsAndSingleVersionBump() {
        LinkStore.clear()
        val before = LinkStore.snapshot().first
        val items = (1..5).map { "rtsp://host/ch$it.ts" to "Channel $it" }
        val (v, list) = LinkStore.addBatch(items)
        assertEquals(5, list.size)
        assertEquals("version must increment by exactly 1", before + 1, v)
        assertEquals("Channel 5", list[0].label)  // newest-first
        assertEquals("Channel 1", list[4].label)
    }

    @Test fun addBatch_emptyItems_noVersionBump() {
        LinkStore.clear()
        val v0 = LinkStore.snapshot().first
        val (v, list) = LinkStore.addBatch(emptyList())
        assertEquals("empty batch must not bump version", v0, v)
        assertTrue(list.isEmpty())
    }

    @Test fun addBatch_dedupsWithinBatch() {
        LinkStore.clear()
        val items = listOf("rtsp://host/ch.ts" to "A", "rtsp://host/ch.ts" to "B")
        val (_, list) = LinkStore.addBatch(items)
        assertEquals("duplicate URL within batch keeps only latest", 1, list.size)
        assertEquals("B", list[0].label)
    }

    @Test fun addBatch_evictsOldestWhenOverCap() {
        LinkStore.clear()
        // Seed just under CAP (12000)
        val seed = (1..11990).map { "rtsp://pre/ch$it.ts" to "Pre$it" }
        LinkStore.addBatch(seed)
        assertEquals(11990, LinkStore.snapshot().second.size)
        // Add 20 more — total 12010 > CAP (12000), should evict 10
        val extra = (1..20).map { "rtsp://new/ch$it.ts" to "New$it" }
        val (_, list) = LinkStore.addBatch(extra)
        assertEquals("must not exceed CAP=12000", 12000, list.size)
        // newest 20 survive
        for (i in 20 downTo 1) assertTrue("New$i should be in list", list.any { it.label == "New$i" })
    }

    @Test fun labelFor_variants() {
        assertEquals("a.mp4", LinkStore.labelFor("http://host/path/a.mp4?x=1"))
        assertEquals("host.tv", LinkStore.labelFor("https://host.tv"))
        assertTrue(LinkStore.labelFor("magnet:?xt=urn:btih:abc&dn=My%20Movie").contains("My Movie"))
    }

    @Test fun addBatch_withGroup_tagsEveryItemAndSerializes() {
        LinkStore.clear()
        val items = (1..3).map { "rtsp://host/ch$it.ts" to "Ch$it" }
        val (v, list) = LinkStore.addBatch(items, group = "My IPTV")
        assertTrue(list.all { it.group == "My IPTV" })
        val arr = JSONObject(ApiJson.links(v, list)).getJSONArray("links")
        assertEquals("My IPTV", arr.getJSONObject(0).getString("group"))
    }

    @Test fun add_singleLink_hasNoGroupKey() {
        LinkStore.clear()
        val (v, list) = LinkStore.add("http://h/a.mp4")
        assertNull(list[0].group)
        // A null group must be omitted from the wire format (not serialized as "null").
        assertFalse(JSONObject(ApiJson.links(v, list)).getJSONArray("links").getJSONObject(0).has("group"))
    }

    @Test fun links_jsonRoundTrips() {
        val list = listOf(LinkStore.Link("id1", """http://h/a b.mp4?q="x"""", "a b.mp4", 123L))
        val o = JSONObject(ApiJson.links(5, list))   // must parse despite the embedded quote
        assertEquals(5, o.getInt("version"))
        val e = o.getJSONArray("links").getJSONObject(0)
        assertEquals("id1", e.getString("id"))
        assertEquals("""http://h/a b.mp4?q="x"""", e.getString("url"))
        assertEquals(123L, e.getLong("addedAt"))
    }
}
