package com.deekshith.droidserve.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgTest {

    private val xml = """
        <tv>
          <programme start="20260101120000 +0000" stop="20260101130000 +0000" channel="a"><title>Noon Show</title></programme>
          <programme start="20260101130000 +0000" stop="20260101140000 +0000" channel="a"><title>One PM &amp; After</title></programme>
          <programme start="20260101120000 +0000" stop="20260101130000 +0000" channel="b"><title>Other</title></programme>
        </tv>
    """.trimIndent()

    @Test fun parse_filtersToWantedChannels() {
        assertEquals(2, Epg.parse(xml, setOf("a")).size)
        assertEquals(3, Epg.parse(xml).size)  // empty wanted = all
    }

    @Test fun parse_unescapesTitleEntities() {
        val p = Epg.parse(xml, setOf("a")).first { it.startMs < it.stopMs && it.title.contains("One PM") }
        assertEquals("One PM & After", p.title)
    }

    @Test fun nowNext_picksCurrentAndUpcoming() {
        val progs = Epg.parse(xml, setOf("a"))
        val noon = progs.first { it.title == "Noon Show" }
        val mid = (noon.startMs + noon.stopMs) / 2
        val nn = Epg.nowNext(progs, "a", mid)
        assertEquals("Noon Show", nn.now)
        assertEquals("One PM & After", nn.next)
        assertTrue(nn.hasData)
        assertEquals(0.5f, nn.progress(mid)!!, 0.01f)
    }

    @Test fun nowNext_beforeAnything_hasNoNowButHasNext() {
        val progs = Epg.parse(xml, setOf("a"))
        val nn = Epg.nowNext(progs, "a", progs.minOf { it.startMs } - 60_000)
        assertNull(nn.now)
        assertEquals("Noon Show", nn.next)
    }

    @Test fun nowNext_unknownChannel_isEmpty() {
        val nn = Epg.nowNext(Epg.parse(xml), "zzz", System.currentTimeMillis())
        assertNull(nn.now); assertNull(nn.next)
        assertTrue(!nn.hasData)
    }

    @Test fun parse_missingStop_defaultsToOneHour() {
        val x = """<tv><programme start="20260101120000 +0000" channel="a"><title>No Stop</title></programme></tv>"""
        val p = Epg.parse(x, setOf("a"))
        assertEquals(1, p.size)
        assertEquals(3_600_000, p[0].stopMs - p[0].startMs)
    }
}
