package com.deekshith.droidserve

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ThumbCacheTest {

    @Test fun keyIsStableAndInputSensitive() {
        val base = ThumbCache.key("doc", 100L, 200L, 320)
        assertEquals(base, ThumbCache.key("doc", 100L, 200L, 320))
        assertNotEquals(base, ThumbCache.key("doc", 101L, 200L, 320))   // mtime changed
        assertNotEquals(base, ThumbCache.key("doc", 100L, 201L, 320))   // size changed
        assertNotEquals(base, ThumbCache.key("doc", 100L, 200L, 160))   // width changed
        assertNotEquals(base, ThumbCache.key("other", 100L, 200L, 320)) // docId changed
    }

    @Test fun etagIsQuotedKey() {
        val k = ThumbCache.key("d", 1L, 2L, 320)
        assertEquals("\"$k\"", ThumbCache.etag(k))
    }

    @Test fun pruneEvictsOldestUntilUnderCap() {
        val dir = File(System.getProperty("java.io.tmpdir"), "dsthumb-${System.nanoTime()}")
        val cache = ThumbCache(dir, maxBytes = 300)
        for (i in 0 until 5) {
            cache.put("k$i", ByteArray(100)).setLastModified(1_000L + i)   // k0 oldest … k4 newest
        }
        cache.prune()   // 500B > 300B cap → evict oldest down to the 270B (90%) low-water mark
        val remaining = dir.listFiles()!!.filter { it.isFile }
        assertTrue("under low-water mark", remaining.sumOf { it.length() } <= 270)
        assertFalse("oldest evicted", cache.file("k0").exists())
        assertTrue("newest kept", cache.file("k4").exists())
        dir.deleteRecursively()
    }

    @Test fun getTreatsMissingOrEmptyAsMiss() {
        val dir = File(System.getProperty("java.io.tmpdir"), "dsthumb-${System.nanoTime()}")
        val cache = ThumbCache(dir)
        assertNull(cache.get("nope"))
        cache.put("empty", ByteArray(0))
        assertNull("zero-length is a miss", cache.get("empty"))
        cache.put("real", ByteArray(10))
        assertNotNull(cache.get("real"))
        dir.deleteRecursively()
    }
}
