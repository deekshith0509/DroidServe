package com.deekshith.droidserve

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Real-time phone -> TV control channel.
 *
 * The phone drops a "cast" command (a fully-resolved, token-carrying media URL) into this
 * queue when the user taps "Play on TV" in the web UI. The TV companion app long-polls
 * `/api/tv/poll`, which blocks on [poll] until a command arrives (or the timeout elapses),
 * then fires an ACTION_VIEW intent so the TV's own default player (VLC / MX / native) opens
 * the stream. No built-in player: we delegate to the performant native app, by design.
 *
 * Kept as a process-wide singleton so it survives across HttpServer restarts within the
 * same service process, and framework-free so it is unit-testable on the JVM.
 */
object CastQueue {

    /** One instruction pushed from a controller (phone) to a player (TV). */
    data class Command(val action: String, val url: String, val mime: String, val subUrl: String? = null)

    // A short queue: casts are rare, human-paced events. Bounded so a disconnected TV can't
    // let commands pile up unboundedly; oldest is dropped on overflow.
    private const val CAPACITY = 16
    private val queue = LinkedBlockingQueue<Command>(CAPACITY)

    /** Enqueue a command from the controller. Non-blocking; drops the oldest on overflow. */
    fun cast(url: String, mime: String, action: String = "play", subUrl: String? = null) {
        val cmd = Command(action, url, mime, subUrl)
        if (!queue.offer(cmd)) {
            queue.poll()          // make room by discarding the stalest command
            queue.offer(cmd)
        }
    }

    /**
     * Block up to [timeoutMs] for the next command. Returns null on timeout so the HTTP
     * long-poll can return 204 and the client immediately re-polls (standard long-poll loop).
     */
    fun poll(timeoutMs: Long): Command? =
        try { queue.poll(timeoutMs, TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { null }

    /** Drop any pending commands (used when the server stops). */
    fun clear() = queue.clear()

    /** Current pending count — exposed for diagnostics/tests. */
    fun pending(): Int = queue.size
}
