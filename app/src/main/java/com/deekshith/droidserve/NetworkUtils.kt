package com.deekshith.droidserve

import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface

object NetworkUtils {

    fun getLocalIpAddress(): String {
        // Check common named interfaces first (fast path)
        for (name in listOf("wlan0", "ap0", "rndis0", "swlan0", "eth0", "rmnet0")) {
            getIpForInterface(name)?.let { return it }
        }
        // Enumerate all interfaces
        try {
            NetworkInterface.getNetworkInterfaces()?.asSequence()
                ?.filter { !it.isLoopback && it.isUp }
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress }
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

    private fun getIpForInterface(name: String): String? {
        return try {
            val iface = NetworkInterface.getByName(name) ?: return null
            if (!iface.isUp || iface.isLoopback) return null
            iface.inetAddresses.asSequence()
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } catch (_: Exception) { null }
    }
}