package com.deekshith.droidserve

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteBusTest {

    @Test fun nowPlaying_roundTripsJson() {
        RemoteBus.setNowPlaying("Star Maa", 3, 10)
        val o = JSONObject(RemoteBus.nowPlayingJson())
        assertEquals("Star Maa", o.getString("label"))
        assertEquals(3, o.getInt("idx"))
        assertEquals(10, o.getInt("total"))
    }

    @Test fun awaitCommand_wakesOnPost() {
        val v0 = RemoteBus.commandVersion()
        Thread { Thread.sleep(80); RemoteBus.postCommand("down") }.start()
        val (v, c) = RemoteBus.awaitCommand(v0, 3000)
        assertTrue(v > v0)
        assertEquals("down", c)
    }

    @Test fun awaitCommand_returnsImmediatelyWhenBehind() {
        RemoteBus.postCommand("up")
        val (_, c) = RemoteBus.awaitCommand(-999, 2000)
        assertEquals("up", c)
    }
}
