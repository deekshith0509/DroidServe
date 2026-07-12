package com.deekshith.droidserve

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the native-client JSON API. These parse the exact bytes the server
 * emits (via [ApiJson]) with a real JSON parser and assert the fields the Android TV client
 * (DroidServeClient) reads. If the wire format drifts from what the TV app expects, these fail.
 */
class ApiJsonTest {

    // ---- /api/info ---------------------------------------------------------

    @Test fun info_roundTrips() {
        val json = ApiJson.info(
            name = "My Phone", ip = "192.168.1.5", port = 8080,
            device = "Google Pixel", auth = true, tokenQuery = "?tok=abc123"
        )
        val o = JSONObject(json)
        assertEquals("My Phone", o.getString("name"))
        assertEquals("192.168.1.5", o.getString("ip"))
        assertEquals(8080, o.getInt("port"))
        assertEquals("Google Pixel", o.getString("device"))
        assertTrue(o.getBoolean("auth"))
        assertEquals("?tok=abc123", o.getString("tokenQuery"))
        assertEquals(1, o.getInt("apiVersion"))
    }

    @Test fun info_escapesQuotesAndBackslashInTitle() {
        val json = ApiJson.info(
            name = """He said "hi"\done""", ip = "10.0.0.1", port = 9,
            device = "d", auth = false, tokenQuery = ""
        )
        val o = JSONObject(json)   // must not throw despite quotes/backslash
        assertEquals("""He said "hi"\done""", o.getString("name"))
        assertFalse(o.getBoolean("auth"))
        assertEquals("", o.getString("tokenQuery"))
    }

    // ---- /api/list ---------------------------------------------------------

    @Test fun list_rootRoundTrips() {
        val rows = listOf(
            ApiJson.Row("Movies", isDir = true, size = 0, modified = 100, mime = "inode/directory"),
            ApiJson.Row("clip.mp4", isDir = false, size = 2048, modified = 200, mime = "video/mp4")
        )
        val o = JSONObject(ApiJson.list("", rows, ""))
        assertEquals("", o.getString("path"))
        val arr = o.getJSONArray("entries")
        assertEquals(2, arr.length())

        val dir = arr.getJSONObject(0)
        assertEquals("Movies", dir.getString("name"))
        assertTrue(dir.getBoolean("isDir"))
        assertEquals("/Movies", dir.getString("url"))   // absolute-from-root, no token

        val file = arr.getJSONObject(1)
        assertEquals("clip.mp4", file.getString("name"))
        assertFalse(file.getBoolean("isDir"))
        assertEquals(2048L, file.getLong("size"))
        assertEquals("video/mp4", file.getString("mime"))
        assertEquals("/clip.mp4", file.getString("url"))
    }

    @Test fun list_nestedPathBuildsEncodedUrls() {
        val rows = listOf(
            ApiJson.Row("a b.mp4", isDir = false, size = 1, modified = 0, mime = "video/mp4")
        )
        val o = JSONObject(ApiJson.list("My Vids/2024", rows, "?tok=t"))
        assertEquals("My Vids/2024", o.getString("path"))
        val e = o.getJSONArray("entries").getJSONObject(0)
        // Each path segment and the name must be percent-encoded; token appended.
        assertEquals("/My%20Vids/2024/a%20b.mp4?tok=t", e.getString("url"))
    }

    @Test fun list_specialCharsInNameStayValidJson() {
        val rows = listOf(
            ApiJson.Row("""weird "name" \ tab	end""", isDir = false, size = 0, modified = 0, mime = "text/plain")
        )
        val o = JSONObject(ApiJson.list("", rows, ""))   // must parse
        val e = o.getJSONArray("entries").getJSONObject(0)
        assertEquals("""weird "name" \ tab	end""", e.getString("name"))
    }

    @Test fun list_emptyFolder() {
        val o = JSONObject(ApiJson.list("empty", emptyList(), ""))
        assertEquals(0, o.getJSONArray("entries").length())
    }

    @Test fun list_videoRowIncludesSubUrlWhenPresent() {
        val rows = listOf(
            ApiJson.Row("movie.mp4", isDir = false, size = 1, modified = 0, mime = "video/mp4",
                subUrl = "/movie.en.srt?vtt=1"),
            ApiJson.Row("plain.mp4", isDir = false, size = 1, modified = 0, mime = "video/mp4")
        )
        val arr = JSONObject(ApiJson.list("", rows, "")).getJSONArray("entries")
        assertEquals("/movie.en.srt?vtt=1", arr.getJSONObject(0).getString("subUrl"))
        // Null subUrl must be omitted entirely (not serialized as "null").
        assertFalse(arr.getJSONObject(1).has("subUrl"))
    }

    @Test fun castCommand_includesSubUrlWhenPresent() {
        val withSub = JSONObject(ApiJson.castCommand("play", "http://h/v.mp4", "video/mp4", "http://h/v.srt?vtt=1"))
        assertEquals("http://h/v.srt?vtt=1", withSub.getString("subUrl"))
        val without = JSONObject(ApiJson.castCommand("play", "http://h/v.mp4", "video/mp4"))
        assertFalse(without.has("subUrl"))
    }

    @Test fun escape_controlCharsBecomeUnicodeEscapes() {
        assertEquals("\\u0000", ApiJson.escape("\u0000"))
        assertEquals("a\\nb", ApiJson.escape("a\nb"))
    }
}
