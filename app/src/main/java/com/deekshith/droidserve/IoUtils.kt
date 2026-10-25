package com.deekshith.droidserve

import java.io.InputStream

/** Bounded InputStream → UTF-8 String. Reads at most [max] bytes; never throws on truncation. */
internal fun readCapped(input: InputStream, max: Int): String {
    val buf = ByteArray(max)
    var total = 0
    while (total < max) {
        val n = input.read(buf, total, max - total)
        if (n < 0) break
        total += n
    }
    return String(buf, 0, total, Charsets.UTF_8)
}
