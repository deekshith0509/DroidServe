package com.deekshith.droidserve.tv

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL

/**
 * Subnet probe used ONLY as a discovery fallback (mDNS is primary).
 *
 * Many consumer routers block multicast between clients, so a reachable phone server never
 * shows up over mDNS. This probe fills the SAME picker list so servers still appear. The user
 * still chooses; nothing is auto-selected.
 *
 * Designed to be a good network citizen so it doesn't starve other tools sharing the TV's
 * single Wi-Fi NIC (e.g. atvtools controlling the TV over the network at the same time):
 *   - Bounded concurrency (a small semaphore) instead of ~1000 simultaneous sockets.
 *   - A fast, cheap TCP-connect liveness check first; a full HTTP GET only happens for hosts
 *     whose port actually accepts a connection (almost always just the real server).
 *   - Short timeouts, and the whole pass is cancellable via coroutine cancellation.
 * The ViewModel calls this on an adaptive schedule (slower once servers are known).
 */
class SubnetScanner(context: Context) {

    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    companion object {
        // Only the realistic DroidServe ports; the app defaults to 8080.
        private val PORTS = intArrayOf(8080, 8081, 8888)
        // Cap simultaneous sockets so we never flood the NIC / exhaust fds. This leaves plenty
        // of headroom for other tools sharing the TV's Wi-Fi (e.g. atvtools) while still
        // sweeping a /24 within a few seconds.
        private const val MAX_CONCURRENT = 24
        private const val TCP_CONNECT_TIMEOUT = 300   // fast liveness check
        private const val HTTP_TIMEOUT = 800          // only for hosts that accepted TCP
    }

    /** Scan the local /24 and return every DroidServe server that answers /api/info. */
    suspend fun scan(): List<DiscoveredServer> {
        val prefix = localSubnetPrefix() ?: return emptyList()
        val gate = Semaphore(MAX_CONCURRENT)
        return coroutineScope {
            (1..254).map { host ->
                async(Dispatchers.IO) {
                    gate.withPermit { probe("$prefix$host") }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun probe(ip: String): DiscoveredServer? {
        for (port in PORTS) {
            // Cheap gate: is anything even listening? Skips the expensive HTTP path for the
            // ~253 dead hosts on a typical subnet.
            if (!tcpOpen(ip, port)) continue
            verify(ip, port)?.let { return it }
        }
        return null
    }

    private fun tcpOpen(ip: String, port: Int): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, port), TCP_CONNECT_TIMEOUT)
            true
        }
    } catch (_: Exception) { false }

    private fun verify(ip: String, port: Int): DiscoveredServer? = try {
        val conn = (URL("http://$ip:$port/api/info").openConnection() as HttpURLConnection).apply {
            connectTimeout = HTTP_TIMEOUT
            readTimeout = HTTP_TIMEOUT
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode != 200) null
            else {
                val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                if (!o.has("apiVersion")) null
                else DiscoveredServer(o.optString("name", "DroidServe"), ip, port)
            }
        } finally { conn.disconnect() }
    } catch (_: Exception) { null }

    /** The /24 prefix of the TV's own Wi-Fi IPv4 address, e.g. "192.168.1." */
    private fun localSubnetPrefix(): String? {
        @Suppress("DEPRECATION")
        wifi?.connectionInfo?.ipAddress?.takeIf { it != 0 }?.let { raw ->
            return "%d.%d.%d.".format(raw and 0xff, raw shr 8 and 0xff, raw shr 16 and 0xff)
        }
        try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress?.let { return it.substringBeforeLast('.') + "." }
        } catch (_: Exception) {}
        return null
    }
}
