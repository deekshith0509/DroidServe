package com.deekshith.droidserve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the pure HLS-stream vs. IPTV-collection discriminator. */
class LinkClassifierTest {

    @Test fun hlsMasterPlaylist_isStream() {
        val body = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1280000,RESOLUTION=1280x720
            chunklist_720.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2560000,RESOLUTION=1920x1080
            chunklist_1080.m3u8
        """.trimIndent()
        assertEquals(LinkClassifier.Kind.HLS_STREAM, LinkClassifier.classify(body))
    }

    @Test fun hlsMediaPlaylist_segmentList_isStream() {
        // Many #EXTINF segments, but an #EXT-X- tag marks it as ONE stream, not many links.
        val body = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXT-X-VERSION:3
            #EXTINF:9.9,
            seg0.ts
            #EXTINF:9.9,
            seg1.ts
            #EXTINF:9.9,
            seg2.ts
        """.trimIndent()
        assertEquals(LinkClassifier.Kind.HLS_STREAM, LinkClassifier.classify(body))
    }

    @Test fun iptvChannelList_isCollection() {
        val body = """
            #EXTM3U
            #EXTINF:-1 group-title="News",BBC World
            http://stream.example/bbc.m3u8
            #EXTINF:-1 group-title="Sports",ESPN
            http://stream.example/espn.ts
            #EXTINF:-1,CNN
            http://stream.example/cnn
        """.trimIndent()
        assertEquals(LinkClassifier.Kind.COLLECTION, LinkClassifier.classify(body))
    }

    @Test fun singleBareStream_isSingle() {
        assertEquals(LinkClassifier.Kind.SINGLE,
            LinkClassifier.classify("#EXTM3U\nhttp://stream.example/only.m3u8"))
    }

    @Test fun headerOnly_isSingle() {
        assertEquals(LinkClassifier.Kind.SINGLE, LinkClassifier.classify("#EXTM3U\n"))
    }

    @Test fun looksLikeM3u_detectsHeaderIgnoringLeadingSpace() {
        assertTrue(LinkClassifier.looksLikeM3u("  \n#EXTM3U\nhttp://x/a.ts"))
        assertFalse(LinkClassifier.looksLikeM3u("<html><body>not a playlist</body></html>"))
    }
}
