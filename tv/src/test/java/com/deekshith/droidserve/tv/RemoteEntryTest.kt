package com.deekshith.droidserve.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure classification tests for RemoteEntry. These pin the routing decision the TV makes on a
 * click: text/images render in-app; only video/audio go to an external player. Regression guard
 * for the crash where clicking a .srt (application/x-subrip) was punted to a video player.
 */
class RemoteEntryTest {

    private fun entry(name: String, mime: String) =
        RemoteEntry(name = name, isDir = false, size = 1, modified = 0, mime = mime, url = "/x")

    @Test fun srt_isTextNotPlayable() {
        val e = entry("movie.en.srt", "application/x-subrip")
        assertTrue("srt must render in-app", e.isText)
        assertFalse(e.isPlayable)
        assertFalse(e.isVideo)
    }

    @Test fun vtt_isText() {
        assertTrue(entry("cc.vtt", "text/vtt").isText)
    }

    @Test fun plainTextTypes_areText() {
        assertTrue(entry("a.txt", "text/plain").isText)
        assertTrue(entry("d.json", "application/json").isText)
        assertTrue(entry("e.xml", "application/xml").isText)
    }

    @Test fun octetStreamWithTextExt_isText() {
        // Server sometimes reports octet-stream; fall back to the extension.
        assertTrue(entry("notes.log", "application/octet-stream").isText)
        assertTrue(entry("build.gradle", "application/octet-stream").isText)
    }

    @Test fun octetStreamBinary_isNotText() {
        assertFalse(entry("blob.bin", "application/octet-stream").isText)
    }

    @Test fun video_and_audio_arePlayableNotText() {
        val v = entry("clip.mkv", "video/x-matroska")
        assertTrue(v.isVideo); assertTrue(v.isPlayable); assertFalse(v.isText)
        val a = entry("song.mp3", "audio/mpeg")
        assertTrue(a.isAudio); assertTrue(a.isPlayable); assertFalse(a.isText)
    }

    @Test fun image_isImageNotText() {
        val i = entry("pic.png", "image/png")
        assertTrue(i.isImage); assertFalse(i.isText); assertFalse(i.isPlayable)
    }
}
