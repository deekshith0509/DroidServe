package com.deekshith.droidserve

import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface

object NetworkUtils {

    fun getLocalIpAddress(): String {
        // Prefer well-known LAN-facing interfaces: Wi-Fi, hotspot/AP, USB & Ethernet tether.
        // (Cellular rmnet is intentionally excluded — its IP isn't reachable by LAN clients.)
        for (name in listOf("wlan0", "ap0", "swlan0", "rndis0", "eth0")) {
            getIpForInterface(name)?.let { return it }
        }
        // Enumerate; prefer a site-local (192.168/10/172.16) address over anything else.
        try {
            val candidates = NetworkInterface.getNetworkInterfaces()?.asSequence()
                ?.filter { it.isUp && !it.isLoopback }
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.filterIsInstance<Inet4Address>()
                ?.filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.toList().orEmpty()
            (candidates.firstOrNull { it.isSiteLocalAddress } ?: candidates.firstOrNull())
                ?.hostAddress?.let { return it }
        } catch (_: Exception) {}
        // UDP trick — no data actually sent
        try {
            DatagramSocket().use { s ->
                s.connect(InetSocketAddress("8.8.8.8", 80))
                val ip = s.localAddress.hostAddress
                if (!ip.isNullOrBlank() && ip != "0.0.0.0") return ip
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    /** Every non-loopback IPv4 address keyed by interface name — full network transparency. */
    fun allIpv4(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            NetworkInterface.getNetworkInterfaces()?.asSequence()
                ?.filter { it.isUp && !it.isLoopback }
                ?.forEach { iface ->
                    iface.inetAddresses.asSequence()
                        .filterIsInstance<Inet4Address>()
                        .filter { !it.isLoopbackAddress }
                        .forEach { addr -> addr.hostAddress?.let { out.add(iface.name to it) } }
                }
        } catch (_: Exception) {}
        return out
    }

    private fun getIpForInterface(name: String): String? {
        return try {
            val iface = NetworkInterface.getByName(name) ?: return null
            if (!iface.isUp || iface.isLoopback) return null
            iface.inetAddresses.asSequence()
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.hostAddress
        } catch (_: Exception) { null }
    }
}