package com.deekshith.droidserve

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory ring buffer of recently served HTTP requests. Surfaced live in the app
 * (and can be exposed over the web) so nothing about server activity is hidden.
 */
object ServerLog {

    data class Entry(
        val wallTime: Long,     // System.currentTimeMillis at completion
        val client: String,     // remote IP
        val method: String,
        val path: String,
        val status: Int,
        val ms: Long
    )

    private const val MAX = 300
    private val buffer = ArrayDeque<Entry>(MAX)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    /** Newest-first snapshot of recent requests. */
    val entries = _entries.asStateFlow()

    @Synchronized
    fun record(client: String, method: String, path: String, status: Int, ms: Long) {
        buffer.addLast(Entry(System.currentTimeMillis(), client, method, path, status, ms))
        while (buffer.size > MAX) buffer.removeFirst()
        _entries.value = buffer.toList().asReversed()
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        _entries.value = emptyList()
    }
}
