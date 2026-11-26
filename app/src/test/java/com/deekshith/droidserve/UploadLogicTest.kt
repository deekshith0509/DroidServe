package com.deekshith.droidserve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadLogicTest {

    // ── sanitizeFilename ────────────────────────────────────────────────────
    @Test fun keepsPlainName() {
        assertEquals("movie.mp4", UploadLogic.sanitizeFilename("movie.mp4"))
    }

    @Test fun rejectsPathSeparators() {
        assertNull(UploadLogic.sanitizeFilename("a/b.txt"))
        assertNull(UploadLogic.sanitizeFilename("a\\b.txt"))
    }

    @Test fun rejectsTraversal() {
        assertNull(UploadLogic.sanitizeFilename(".."))
        assertNull(UploadLogic.sanitizeFilename("."))
    }

    @Test fun rejectsControlChars() {
        assertNull(UploadLogic.sanitizeFilename("ev\u0000il.txt"))
        assertNull(UploadLogic.sanitizeFilename("ev\nil.txt"))
        assertNull(UploadLogic.sanitizeFilename("ev\u007Fil.txt"))
    }

    @Test fun stripsTrailingDotsAndSpaces() {
        assertEquals("file", UploadLogic.sanitizeFilename("file.  "))
        assertEquals("file", UploadLogic.sanitizeFilename("  file  "))
    }

    @Test fun emptyAfterTrimIsRejected() {
        assertNull(UploadLogic.sanitizeFilename("   "))
        assertNull(UploadLogic.sanitizeFilename("..."))
    }

    @Test fun unicodeNamePreserved() {
        // Telugu filename survives (NFC-normalised, non-control).
        val n = UploadLogic.sanitizeFilename("సినిమా.mkv")
        assertNotNull(n)
        assertTrue(n!!.endsWith(".mkv"))
    }

    @Test fun truncatesOverlongNamePreservingExtension() {
        val long = "a".repeat(400) + ".mp4"
        val out = UploadLogic.sanitizeFilename(long)!!
        assertTrue(out.toByteArray(Charsets.UTF_8).size <= 255)
        assertTrue(out.endsWith(".mp4"))
    }

    // ── resolveCollision ────────────────────────────────────────────────────
    @Test fun noCollisionReturnsSame() {
        assertEquals("a.txt", UploadLogic.resolveCollision("a.txt", setOf("b.txt")))
    }

    @Test fun suffixesOnCollision() {
        assertEquals("a (1).txt", UploadLogic.resolveCollision("a.txt", setOf("a.txt")))
    }

    @Test fun suffixesPastMultipleCollisions() {
        assertEquals("a (3).txt", UploadLogic.resolveCollision("a.txt", setOf("a.txt", "a (1).txt", "a (2).txt")))
    }

    @Test fun suffixesExtensionlessName() {
        assertEquals("README (1)", UploadLogic.resolveCollision("README", setOf("README")))
    }

    // ── originHostMatches (CSRF) ──────────────────────────────────────────────
    @Test fun originMatchesHostWithPort() {
        assertTrue(UploadLogic.originHostMatches("http://192.168.1.5:8080", "192.168.1.5:8080"))
    }

    @Test fun refererUrlMatchesHost() {
        assertTrue(UploadLogic.originHostMatches("http://192.168.1.5:8080/Movies/", "192.168.1.5:8080"))
    }

    @Test fun crossOriginRejected() {
        assertFalse(UploadLogic.originHostMatches("http://evil.example.com", "192.168.1.5:8080"))
    }

    @Test fun missingOriginRejected() {
        assertFalse(UploadLogic.originHostMatches(null, "192.168.1.5:8080"))
        assertFalse(UploadLogic.originHostMatches("", "192.168.1.5:8080"))
    }

    // ── OverwritePolicy parsing ───────────────────────────────────────────────
    @Test fun overwritePolicyDefaultsToSuffix() {
        assertEquals(OverwritePolicy.SUFFIX, OverwritePolicy.from(null))
        assertEquals(OverwritePolicy.SUFFIX, OverwritePolicy.from("garbage"))
    }

    @Test fun overwritePolicyParses() {
        assertEquals(OverwritePolicy.OVERWRITE, OverwritePolicy.from("overwrite"))
        assertEquals(OverwritePolicy.REJECT, OverwritePolicy.from("REJECT"))
    }
}
