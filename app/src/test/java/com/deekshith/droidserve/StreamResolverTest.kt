package com.deekshith.droidserve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tests for the pure HTML → direct-media-URL extraction used to resolve watch/embed pages. */
class StreamResolverTest {

    @Test fun extractsPlayerSourceTag() {
        // Mirrors the real watch-page case: a <source> with no file extension in the URL.
        val html = """<video controls><source src="https://h.example/AgADzBxp1cefm" type="video/x-matroska"></video>"""
        assertEquals("https://h.example/AgADzBxp1cefm",
            StreamResolver.extractMediaUrl(html, "https://h.example/watch/x?hash=y"))
    }

    @Test fun extractsHlsFromEmbedConfig() {
        val html = """<script>var p={file:"https://cdn.x/stream/master.m3u8?t=1",autoplay:true};</script>"""
        assertEquals("https://cdn.x/stream/master.m3u8?t=1",
            StreamResolver.extractMediaUrl(html, "https://cdn.x/e/1"))
    }

    @Test fun absolutizesRelativeSource() {
        val html = """<source src="/media/movie.mp4">"""
        assertEquals("https://h.example/media/movie.mp4",
            StreamResolver.extractMediaUrl(html, "https://h.example/watch/x"))
    }

    @Test fun readsOgVideoMeta() {
        val html = """<meta property="og:video" content="https://h/v.mp4">"""
        assertEquals("https://h/v.mp4", StreamResolver.extractMediaUrl(html, "https://h/p"))
    }

    @Test fun ignoresScriptAndStyleSources() {
        val html = """<script src="https://cdn/plyr.js"></script><link href="https://cdn/app.css">"""
        assertNull(StreamResolver.extractMediaUrl(html, "https://h/p"))
    }

    @Test fun noMediaReturnsNull() {
        assertNull(StreamResolver.extractMediaUrl("<html><body>nothing here</body></html>", "https://h/p"))
    }

    @Test fun resolve_fallsBackToOriginalWhenUnreachable() {
        // Unresolvable host → best-effort passthrough of the original URL (never throws).
        val u = "https://nonexistent.invalid/v.mp4"
        assertEquals(u, StreamResolver.resolve(u).url)
    }

    @Test fun resolve_leavesMagnetAloneAndPlayable() {
        val r = StreamResolver.resolve("magnet:?xt=urn:btih:abc")
        assertEquals("magnet:?xt=urn:btih:abc", r.url)
        assertEquals(true, r.playable)
    }
}
