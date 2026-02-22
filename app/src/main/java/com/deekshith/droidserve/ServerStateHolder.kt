package com.deekshith.droidserve

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

object ServerStateHolder {

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
        _isRunning.value = true
    }

    fun onStopped() {
        _isRunning.value = false
        _ip.value = ""
    }

    fun incrementRequests(): Int {
        val n = counter.incrementAndGet()
        _requestCount.value = n
        return n
    }
}