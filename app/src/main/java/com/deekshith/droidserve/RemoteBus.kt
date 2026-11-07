package com.deekshith.droidserve

/**
 * Tiny phone↔TV remote bus for the in-app channel player. The TV reports what it's playing and
 * long-polls for channel commands; the phone web UI shows "now playing" and sends up/down/jump.
 * In-memory, process-wide (like [CastQueue]) — transient control state, never persisted.
 */
object RemoteBus {
    private val lock = java.lang.Object()
    private var label = ""
    private var idx = 0
    private var total = 0
    private var cmd = ""
    private var cmdVersion = 0

    /** TV → bus: what the player is showing now. */
    fun setNowPlaying(label: String, idx: Int, total: Int) = synchronized(lock) {
        this.label = label; this.idx = idx; this.total = total
    }

    fun nowPlayingJson(): String = synchronized(lock) {
        "{\"label\":\"${ApiJson.escape(label)}\",\"idx\":$idx,\"total\":$total}"
    }

    /** Web UI → bus: a remote command ("up", "down", or a 1-based channel number). */
    fun postCommand(c: String) = synchronized(lock) {
        if (c.isNotEmpty()) { cmd = c; cmdVersion++; lock.notifyAll() }
    }

    /** TV long-poll: block until a command newer than [since] arrives (or [timeoutMs] elapses). */
    fun awaitCommand(since: Int, timeoutMs: Long): Pair<Int, String> = synchronized(lock) {
        if (cmdVersion == since) try { lock.wait(timeoutMs) } catch (_: InterruptedException) {}
        cmdVersion to cmd
    }

    fun commandVersion(): Int = synchronized(lock) { cmdVersion }
}
