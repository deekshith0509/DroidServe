package com.deekshith.droidserve

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

object ServerStateHolder {

    // Last time a request was served (elapsedRealtime) — used for inactivity auto-stop.
    @Volatile
    var lastActivityElapsed: Long = 0L
        private set

    // When the server started (elapsedRealtime) — used to show uptime.
    @Volatile
    var startedAtElapsed: Long = 0L
        private set

    private val _isRunning    = MutableStateFlow(false)
    private val _ip           = MutableStateFlow("")
    private val _port         = MutableStateFlow(8080)
    private val _requestCount = MutableStateFlow(0)

    val isRunning    = _isRunning.asStateFlow()
    val ip           = _ip.asStateFlow()
    val port         = _port.asStateFlow()
    val requestCount = _requestCount.asStateFlow()

    // AtomicInteger as the actual counter — CAS ops, no lock contention
    // across all worker threads. StateFlow updated every request for live UI.
    private val counter = AtomicInteger(0)

    fun onStarted(ip: String, port: Int) {
        counter.set(0)
        _requestCount.value = 0
        _ip.value    = ip
        _port.value  = port
        val now = SystemClock.elapsedRealtime()
        lastActivityElapsed = now
        startedAtElapsed = now
        _isRunning.value = true
    }

    fun onStopped() {
        _isRunning.value = false
        _ip.value = ""
    }

    // Re-publish running state after a process recreation WITHOUT resetting counters/uptime.
    // Used by ACTION_SYNC when the service is alive but the in-memory flag was lost.
    fun resync(ip: String, port: Int) {
        _ip.value = ip
        _port.value = port
        if (startedAtElapsed == 0L) startedAtElapsed = SystemClock.elapsedRealtime()
        _isRunning.value = true
    }

    // Update only the reachable IP after a network change (Wi-Fi <-> mobile data, etc.) while
    // the server keeps running. Counters, uptime and the running flag are untouched. No-op if
    // the IP is unchanged so the StateFlow doesn't churn.
    fun updateIp(ip: String) {
        if (_isRunning.value && ip.isNotEmpty() && _ip.value != ip) _ip.value = ip
    }

    fun incrementRequests(): Int {
        val n = counter.incrementAndGet()
        lastActivityElapsed = SystemClock.elapsedRealtime()
        _requestCount.value = n
        return n
    }
}