package com.deekshith.droidserve

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the real-time phone -> TV cast channel (CastQueue + castCommand JSON). */
class CastQueueTest {

    @After fun cleanup() = CastQueue.clear()

    @Test fun poll_timesOutToNullWhenEmpty() {
        CastQueue.clear()
        val start = System.currentTimeMillis()
        val cmd = CastQueue.poll(120)   // short block
        assertNull(cmd)
        assertTrue("should have blocked ~timeout", System.currentTimeMillis() - start >= 100)
    }

    @Test fun castThenPollReturnsCommand() {
        CastQueue.clear()
        CastQueue.cast("http://x/clip.mp4?tok=t", "video/mp4")
        val cmd = CastQueue.poll(1000)
        assertEquals("play", cmd?.action)
        assertEquals("http://x/clip.mp4?tok=t", cmd?.url)
        assertEquals("video/mp4", cmd?.mime)
    }

    @Test fun fifoOrder() {
        CastQueue.clear()
        CastQueue.cast("u1", "video/mp4")
        CastQueue.cast("u2", "audio/mpeg")
        assertEquals("u1", CastQueue.poll(500)?.url)
        assertEquals("u2", CastQueue.poll(500)?.url)
    }

    @Test fun blockedPollWakesWhenCastArrives() {
        CastQueue.clear()
        val t = Thread { Thread.sleep(80); CastQueue.cast("late", "video/mp4") }
        t.start()
        val cmd = CastQueue.poll(2000)   // should wake as soon as cast() fires
        assertEquals("late", cmd?.url)
    }

    @Test fun castCommand_jsonRoundTrips() {
        val json = ApiJson.castCommand("play", """http://h/a b.mp4?tok="x"""", "video/mp4")
        val o = JSONObject(json)   // must parse despite the embedded quote
        assertEquals("play", o.getString("action"))
        assertEquals("""http://h/a b.mp4?tok="x"""", o.getString("url"))
        assertEquals("video/mp4", o.getString("mime"))
    }
}
