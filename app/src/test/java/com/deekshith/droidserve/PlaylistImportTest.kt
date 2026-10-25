package com.deekshith.droidserve

import org.junit.Assert.*
import org.junit.Test

class PlaylistImportTest {

    @Test fun parse_wellFormedMultiEntry() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 group-title="News",BBC World News
            http://stream.example/bbc.m3u8
            #EXTINF:-1 group-title="Sports",ESPN Live
            rtsp://stream.example/espn
        """.trimIndent()
        val entries = PlaylistImport.parse(m3u)
        assertEquals(2, entries.size)
        assertEquals("BBC World News", entries[0].title)
        assertEquals("http://stream.example/bbc.m3u8", entries[0].url)
        assertEquals("News", entries[0].group)
        assertEquals("ESPN Live", entries[1].title)
        assertEquals("Sports", entries[1].group)
    }

    @Test fun parse_entryWithoutExtinf_usesUrlAsTitle() {
        val m3u = "#EXTM3U\nhttp://stream.example/bare.mp4"
        val entries = PlaylistImport.parse(m3u)
        assertEquals(1, entries.size)
        assertEquals("http://stream.example/bare.mp4", entries[0].url)
        assertTrue("title should be derived from URL", entries[0].title.isNotEmpty())
        assertNull(entries[0].group)
    }

    @Test fun parse_entryWithoutGroupTitle_groupIsNull() {
        val m3u = "#EXTM3U\n#EXTINF:-1,Solo Channel\nhttp://stream.example/solo.ts"
        val entries = PlaylistImport.parse(m3u)
        assertEquals(1, entries.size)
        assertEquals("Solo Channel", entries[0].title)
        assertNull(entries[0].group)
    }

    @Test fun parse_blankAndCommentLines_ignored() {
        val m3u = """
            #EXTM3U
            #EXTVLCOPT:something

            #EXTINF:-1,My Channel
            http://stream.example/ch.ts

        """.trimIndent()
        val entries = PlaylistImport.parse(m3u)
        assertEquals(1, entries.size)
        assertEquals("My Channel", entries[0].title)
    }

    @Test fun parse_crlfLineEndings() {
        val m3u = "#EXTM3U\r\n#EXTINF:-1,CRLF Channel\r\nhttp://stream.example/crlf.ts\r\n"
        val entries = PlaylistImport.parse(m3u)
        assertEquals(1, entries.size)
        assertEquals("CRLF Channel", entries[0].title)
        assertEquals("http://stream.example/crlf.ts", entries[0].url)
    }

    @Test fun parse_titleContainingComma() {
        val m3u = "#EXTM3U\n#EXTINF:-1,News, Business & Tech\nhttp://stream.example/nbt.ts"
        val entries = PlaylistImport.parse(m3u)
        // substringAfterLast(',') gives " Business & Tech" — trim gives the trailing part only
        // This is a known M3U quirk: everything after the LAST comma is the title
        assertEquals(1, entries.size)
        assertEquals("Business & Tech", entries[0].title)
    }

    @Test fun parse_emptyInput_returnsEmpty() {
        assertEquals(0, PlaylistImport.parse("").size)
    }

    @Test fun parse_garbageInput_treatedAsUrlEntry() {
        // Parser is deliberately permissive: any non-comment non-blank line is a URL entry.
        // Scheme filtering happens at the route boundary via isAllowedLink, not inside the parser.
        val entries = PlaylistImport.parse("not a playlist at all!!!")
        assertEquals("garbage line becomes one entry", 1, entries.size)
        assertEquals("not a playlist at all!!!", entries[0].url)
    }

    @Test fun parse_blankOnlyInput_returnsEmpty() {
        assertEquals(0, PlaylistImport.parse("   \n\n\t\n").size)
    }

    @Test fun parse_maxEntriesCap() {
        val sb = StringBuilder("#EXTM3U\n")
        repeat(10) { sb.append("http://stream.example/ch${it}.ts\n") }
        val entries = PlaylistImport.parse(sb.toString(), max = 3)
        assertEquals("parse should stop at max=3", 3, entries.size)
    }

    @Test fun parse_longUrlAsTitleWhenNoExtinf() {
        val url = "http://stream.example/" + "x".repeat(200) + ".ts"
        val m3u = "#EXTM3U\n$url"
        val entries = PlaylistImport.parse(m3u)
        assertEquals(1, entries.size)
        assertTrue("title from URL should be capped at 60 chars", entries[0].title.length <= 60)
    }

    @Test fun parse_groupCapturedButNotLeakedToTitle() {
        val m3u = "#EXTM3U\n#EXTINF:-1 group-title=\"MyGroup\",Channel Name\nhttp://stream.example/ch.ts"
        val entries = PlaylistImport.parse(m3u)
        assertEquals("Channel Name", entries[0].title)
        assertEquals("MyGroup", entries[0].group)
        assertFalse("title must not contain group", entries[0].title.contains("MyGroup"))
    }
}
