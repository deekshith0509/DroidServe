package com.deekshith.droidserve

import org.junit.Assert.*
import org.junit.Test

class FileUtilsPlayableTest {

    @Test fun webNativeFormatsPlayInline() {
        assertTrue(FileUtils.isBrowserPlayable("clip.mp4"))
        assertTrue(FileUtils.isBrowserPlayable("clip.M4V"))
        assertTrue(FileUtils.isBrowserPlayable("clip.webm"))
        assertTrue(FileUtils.isBrowserPlayable("clip.ogv"))
        assertTrue(FileUtils.isBrowserPlayable("song.mp3"))
        assertTrue(FileUtils.isBrowserPlayable("song.ogg"))
        assertTrue(FileUtils.isBrowserPlayable("song.wav"))
        assertTrue(FileUtils.isBrowserPlayable("song.flac"))
        assertTrue(FileUtils.isBrowserPlayable("song.aac"))
        assertTrue(FileUtils.isBrowserPlayable("song.m4a"))
        assertTrue(FileUtils.isBrowserPlayable("song.opus"))
        assertTrue(FileUtils.isBrowserPlayable("song.weba"))
    }

    @Test fun nonWebNativeMediaRedirectsToNativePlayer() {
        assertFalse(FileUtils.isBrowserPlayable("movie.mkv"))
        assertFalse(FileUtils.isBrowserPlayable("movie.avi"))
        assertFalse(FileUtils.isBrowserPlayable("movie.mov"))
        assertFalse(FileUtils.isBrowserPlayable("movie.ts"))
        assertFalse(FileUtils.isBrowserPlayable("movie.wmv"))
        assertFalse(FileUtils.isBrowserPlayable("movie.flv"))
        assertFalse(FileUtils.isBrowserPlayable("clip.mpg"))
        assertFalse(FileUtils.isBrowserPlayable("audio.mka"))
    }

    @Test fun nonMediaIsNotPlayable() {
        assertFalse(FileUtils.isBrowserPlayable("doc.pdf"))
        assertFalse(FileUtils.isBrowserPlayable("notes.txt"))
        assertFalse(FileUtils.isBrowserPlayable("photo.jpg"))
    }
}
