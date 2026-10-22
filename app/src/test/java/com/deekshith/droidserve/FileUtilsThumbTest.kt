package com.deekshith.droidserve

import org.junit.Assert.*
import org.junit.Test

class FileUtilsThumbTest {

    @Test fun onlyImagesAndVideosAreThumbnailable() {
        assertTrue(FileUtils.isThumbnailable("a.jpg"))
        assertTrue(FileUtils.isThumbnailable("A.PNG"))
        assertTrue(FileUtils.isThumbnailable("clip.MP4"))
        assertTrue(FileUtils.isThumbnailable("movie.mkv"))
        assertFalse(FileUtils.isThumbnailable("song.mp3"))
        assertFalse(FileUtils.isThumbnailable("notes.txt"))
        assertFalse(FileUtils.isThumbnailable("doc.pdf"))
        assertFalse(FileUtils.isThumbnailable("app.apk"))
    }

    @Test fun thumbUrlEncodesSegmentsAndAppendsWidthAndToken() {
        assertEquals("/thumb/My%20Vids/a%26b.mp4?w=320", FileUtils.thumbUrlFor("My Vids/a&b.mp4", 320, ""))
        assertEquals("/thumb/x.jpg?w=160&tok=abc", FileUtils.thumbUrlFor("x.jpg", 160, "&tok=abc"))
    }
}
