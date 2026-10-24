package com.deekshith.droidserve

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Test fun labelFor_variants() {
        assertEquals("a.mp4", LinkStore.labelFor("http://host/path/a.mp4?x=1"))
        assertEquals("host.tv", LinkStore.labelFor("https://host.tv"))
        assertTrue(LinkStore.labelFor("magnet:?xt=urn:btih:abc&dn=My%20Movie").contains("My Movie"))
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
