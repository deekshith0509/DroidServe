package com.deekshith.droidserve

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for the pure playlist-group naming used when a URL auto-expands into channels. */
class LinkIngestTest {

    @Test fun groupNameFor_usesFileBaseName() {
        assertEquals("iptv", LinkIngest.groupNameFor("http://host.tv/lists/iptv.m3u"))
        assertEquals("channels", LinkIngest.groupNameFor("https://cdn.example/channels.m3u8?token=abc"))
    }

    @Test fun groupNameFor_fallsBackToHostWhenNoFile() {
        assertEquals("host.tv", LinkIngest.groupNameFor("http://host.tv/"))
        assertEquals("host.tv", LinkIngest.groupNameFor("http://host.tv"))
    }

    @Test fun groupNameFor_cappedLength() {
        val long = "http://h/" + "x".repeat(100) + ".m3u"
        assertEquals(40, LinkIngest.groupNameFor(long).length)
    }
}
