package com.deekshith.droidserve.tv

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

/**
 * Subnet probe used ONLY as a discovery fallback.
 *
 * mDNS/NSD is the primary path, but many consumer routers block multicast between clients
 * (AP/client isolation, IGMP snooping) and some OEM phones suppress NSD in the background —
 * on those networks the phone's HTTP server is reachable but never shows up over mDNS. This
 * probe fills the SAME picker list by trying GET /api/info across the local /24 so servers
 * still appear. The user still sees the list and chooses; nothing is auto-selected.
 */
class SubnetScanner(context: Context) {

    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    companion object {
        private val PORTS = intArrayOf(8080, 8081, 8888, 80)
        private const val CONNECT_TIMEOUT = 350
        private const val READ_TIMEOUT = 500
    }

    /** Scan the local /24 and return every DroidServe server that answers /api/info. */
    suspend fun scan(): List<DiscoveredServer> {
        val prefix = localSubnetPrefix() ?: return emptyList()
        return coroutineScope {
            (1..254).map { host ->
                async(Dispatchers.IO) { probe("$prefix$host") }
            }.awaitAll().filterNotNull()
        }
    }

    private fun probe(ip: String): DiscoveredServer? {
        for (port in PORTS) tryOne(ip, port)?.let { return it }
        return null
    }

    private fun tryOne(ip: String, port: Int): DiscoveredServer? = try {
        val conn = (URL("http://$ip:$port/api/info").openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
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
