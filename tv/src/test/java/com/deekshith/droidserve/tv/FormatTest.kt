package com.deekshith.droidserve.tv

import com.deekshith.droidserve.tv.ui.DsIcons
import com.deekshith.droidserve.tv.ui.LinkKind
import com.deekshith.droidserve.tv.ui.breadcrumb
import com.deekshith.droidserve.tv.ui.fmtTime
import com.deekshith.droidserve.tv.ui.formatSize
import com.deekshith.droidserve.tv.ui.iconFor
import com.deekshith.droidserve.tv.ui.linkKind
import com.deekshith.droidserve.tv.ui.metaFor
import com.deekshith.droidserve.tv.ui.sampleSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {
    private fun entry(name: String, isDir: Boolean = false, mime: String = "", size: Long = 0, sub: String? = null) =
        RemoteEntry(name, isDir, size, 0L, mime, "http://x/$name", null, sub)

    @Test fun formatSize_units() {
        assertEquals("512 B", formatSize(512))
        assertEquals("1023 B", formatSize(1023))
        assertTrue(formatSize(1536).endsWith(" KB"))
        assertTrue(formatSize(5L * 1024 * 1024).endsWith(" MB"))
        assertTrue(formatSize(3L * 1024 * 1024 * 1024).endsWith(" GB"))
    }

    @Test fun iconFor_mapsEveryType() {
        assertEquals(DsIcons.Folder, iconFor(entry("d", isDir = true)))
        assertEquals(DsIcons.Video, iconFor(entry("a.mkv", mime = "video/x-matroska")))
        assertEquals(DsIcons.Audio, iconFor(entry("a.mp3", mime = "audio/mpeg")))
        assertEquals(DsIcons.Image, iconFor(entry("a.jpg", mime = "image/jpeg")))
        assertEquals(DsIcons.Apk, iconFor(entry("a.apk", mime = "application/vnd.android.package-archive")))
        assertEquals(DsIcons.Doc, iconFor(entry("a.bin", mime = "application/octet-stream")))
    }

    @Test fun metaFor_sizeOrFolder() {
        assertEquals("Folder", metaFor(entry("d", isDir = true)))
        assertEquals("512 B", metaFor(entry("f", size = 512)))
    }

    @Test fun breadcrumb_joinsPathSegments() {
        assertEquals("", breadcrumb(""))
        assertEquals("Movies", breadcrumb("Movies"))
        assertEquals("Movies   ›   2024", breadcrumb("Movies/2024"))
        assertEquals("a   ›   b", breadcrumb("/a/b/"))
    }

    @Test fun fmtTime_mmss() {
        assertEquals("0:00", fmtTime(0))
        assertEquals("0:00", fmtTime(-5))
        assertEquals("1:05", fmtTime(65_000))
        assertEquals("10:00", fmtTime(600_000))
    }

    @Test fun linkKind_classifies() {
        assertEquals(LinkKind.MAGNET, linkKind("magnet:?xt=urn:btih:abc"))
        assertEquals(LinkKind.AUDIO, linkKind("http://x/song.mp3"))
        assertEquals(LinkKind.AUDIO, linkKind("http://x/song.flac?x=1"))
        assertEquals(LinkKind.VIDEO, linkKind("http://x/stream.m3u8"))
        assertEquals(LinkKind.VIDEO, linkKind("https://example.com/watch"))
    }

    @Test fun sampleSize_downsamplesLargeImages() {
        assertEquals(1, sampleSize(100, 100, 2560))
        assertEquals(1, sampleSize(0, 0, 2560))
        assertTrue(sampleSize(5120, 5120, 2560) >= 2)
        assertTrue(sampleSize(10240, 10240, 2560) >= 4)
    }
}
