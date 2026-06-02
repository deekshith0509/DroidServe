package com.deekshith.droidserve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic regression tests for the audited fixes. These exercise only framework-free code
 * (no Android Context), so they run on the JVM via `testDebugUnitTest`.
 */
class LogicTest {

    // ---- parseByteRange (B12 overflow, B19 single-range, B20 suffix clamp) -------------------

    @Test fun range_simple() {
        assertEquals(0L to 99L, parseByteRange("bytes=0-99", 1000))
    }

    @Test fun range_openEnded() {
        assertEquals(100L to 999L, parseByteRange("bytes=100-", 1000))
    }

    @Test fun range_suffix() {
        assertEquals(900L to 999L, parseByteRange("bytes=-100", 1000))
    }

    @Test fun range_suffixLargerThanFileClampsToWhole() {   // B20
        assertEquals(0L to 999L, parseByteRange("bytes=-5000", 1000))
    }

    @Test fun range_endBeyondSizeIsClamped() {
        assertEquals(500L to 999L, parseByteRange("bytes=500-100000", 1000))
    }

    @Test fun range_startBeyondSizeIsUnsatisfiable() {
        assertNull(parseByteRange("bytes=1000-1001", 1000))
    }

    @Test fun range_reversedIsUnsatisfiable() {
        assertNull(parseByteRange("bytes=500-499", 1000))
    }

    @Test fun range_overflowDoesNotThrow() {                // B12
        assertNull(parseByteRange("bytes=0-99999999999999999999999", 1000))
    }

    @Test fun range_malformedReturnsNull() {
        assertNull(parseByteRange("bytes=abc", 1000))
    }

    @Test fun range_zeroSizeFile() {
        assertNull(parseByteRange("bytes=0-", 0))
    }

    // ---- FileUtils.encodeSeg (B2 XSS / proper percent-encoding) ------------------------------

    @Test fun encode_keepsUnreserved() {
        assertEquals("file-1.0_name~.txt", FileUtils.encodeSeg("file-1.0_name~.txt"))
    }

    @Test fun encode_space() {
        assertEquals("a%20b.txt", FileUtils.encodeSeg("a b.txt"))
    }

    @Test fun encode_plusAndPercentAndAmp() {
        assertEquals("a%2Bb", FileUtils.encodeSeg("a+b"))
        assertEquals("100%25", FileUtils.encodeSeg("100%"))
        assertEquals("a%26b", FileUtils.encodeSeg("a&b"))
    }

    @Test fun encode_utf8() {
        assertEquals("caf%C3%A9", FileUtils.encodeSeg("café"))
    }

    @Test fun encode_xssPayloadHasNoRawHtmlMetachars() {     // B2 regression
        val out = FileUtils.encodeSeg("\"><script>alert(1)</script>")
        for (c in listOf('"', '<', '>', '&', ' ', '\'')) {
            assertFalse("must not contain raw '$c'", out.contains(c))
        }
    }

    // ---- FileUtils.escHtml -------------------------------------------------------------------

    @Test fun escHtml_escapesMetachars() {
        assertEquals("a&lt;b&gt;&amp;&quot;c", FileUtils.escHtml("a<b>&\"c"))
    }

    @Test fun escHtml_passesPlainThrough() {
        assertEquals("plain name", FileUtils.escHtml("plain name"))
    }

    // ---- FileUtils.isHiddenOrSystem ----------------------------------------------------------

    @Test fun hidden_dotAndUnderscoreAreHidden() {
        assertTrue(FileUtils.isHiddenOrSystem(".secret"))
        assertTrue(FileUtils.isHiddenOrSystem("__system"))
    }

    @Test fun hidden_normalIsVisible() {
        assertFalse(FileUtils.isHiddenOrSystem("normal.txt"))
        assertFalse(FileUtils.isHiddenOrSystem(""))
    }

    // ---- FileUtils.formatSize (locale-robust assertions) -------------------------------------

    @Test fun size_bytes() {
        assertEquals("512 B", FileUtils.formatSize(512))
        assertEquals("1023 B", FileUtils.formatSize(1023))
    }

    @Test fun size_kbAndMbSuffixes() {
        assertTrue(FileUtils.formatSize(1536).endsWith(" KB"))
        assertTrue(FileUtils.formatSize(5L * 1024 * 1024).endsWith(" MB"))
        assertTrue(FileUtils.formatSize(3L * 1024 * 1024 * 1024).endsWith(" GB"))
    }
}
