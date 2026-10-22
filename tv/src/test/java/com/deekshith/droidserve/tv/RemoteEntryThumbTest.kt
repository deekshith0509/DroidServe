package com.deekshith.droidserve.tv

import org.junit.Assert.*
import org.junit.Test

class RemoteEntryThumbTest {

    @Test fun thumbnailableReflectsMime() {
        assertTrue(RemoteEntry("a.jpg", false, 1, 0, "image/jpeg", "u").isThumbnailable)
        assertTrue(RemoteEntry("a.mp4", false, 1, 0, "video/mp4", "u").isThumbnailable)
        assertFalse(RemoteEntry("a.mp3", false, 1, 0, "audio/mpeg", "u").isThumbnailable)
        assertFalse(RemoteEntry("d", true, 0, 0, "inode/directory", "u").isThumbnailable)
    }

    @Test fun thumbUrlDefaultsNullAndIsCarried() {
        assertNull(RemoteEntry("a.jpg", false, 1, 0, "image/jpeg", "u").thumbUrl)
        assertEquals(
            "http://h/thumb/a.jpg?w=320",
            RemoteEntry("a.jpg", false, 1, 0, "image/jpeg", "u", thumbUrl = "http://h/thumb/a.jpg?w=320").thumbUrl
        )
    }
}
