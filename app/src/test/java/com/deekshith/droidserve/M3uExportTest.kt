package com.deekshith.droidserve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uExportTest {

    @Test fun export_writesHeaderAndEntryWithMetadata() {
        val links = listOf(
            LinkStore.Link("id1", "http://h/live.ts", "Star Maa", 1L, true, "Telugu", "http://logo.png",
                "StarMaa.in", "SmartTV/1", "http://ref/"),
        )
        val out = M3uExport.export(links)
        assertTrue(out.startsWith("#EXTM3U"))
        assertTrue(out.contains("tvg-id=\"StarMaa.in\""))
        assertTrue(out.contains("tvg-logo=\"http://logo.png\""))
        assertTrue(out.contains("group-title=\"Telugu\""))
        assertTrue(out.contains(",Star Maa"))
        assertTrue(out.contains("#EXTVLCOPT:http-user-agent=SmartTV/1"))
        assertTrue(out.contains("#EXTVLCOPT:http-referrer=http://ref/"))
        assertTrue(out.contains("http://h/live.ts"))
    }

    @Test fun export_roundTripsThroughPlaylistImport() {
        val links = listOf(
            LinkStore.Link("a", "http://h/a.ts", "A", 1L, true, "News", "http://a.png", "a.id", "UA-A", null),
            LinkStore.Link("b", "http://h/b.ts", "B", 2L, true, null, null, null, null, "http://ref-b/"),
        )
        val parsed = PlaylistImport.parse(M3uExport.export(links))
        assertEquals(2, parsed.size)
        assertEquals("A", parsed[0].title)
        assertEquals("a.id", parsed[0].tvgId)
        assertEquals("News", parsed[0].group)
        assertEquals("UA-A", parsed[0].userAgent)
        assertEquals("http://h/b.ts", parsed[1].url)
        assertEquals("http://ref-b/", parsed[1].referrer)
    }
}
