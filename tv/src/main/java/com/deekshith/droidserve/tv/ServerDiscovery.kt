package com.deekshith.droidserve.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Discovers DroidServe phone servers advertised via mDNS/NSD ("_droidserve._tcp").
 *
 * Emits the full set of currently-known servers on every change, so the UI can render a
 * live picker with no manual IP typing — the whole reason this app exists.
 */
class ServerDiscovery(context: Context) {

    companion object {
        private const val TAG = "DroidServeTv"
        private const val SERVICE_TYPE = "_droidserve._tcp."
    }

    private val nsdManager = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager

    fun discover(): Flow<List<DiscoveredServer>> = callbackFlow {
        val found = LinkedHashMap<String, DiscoveredServer>()

        fun emit() { trySend(found.values.toList()) }

        // Resolving turns a discovered service name into host + port.
        fun resolve(info: NsdServiceInfo) {
            val cb = object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "resolve failed: $errorCode")
                }
                override fun onServiceResolved(si: NsdServiceInfo) {
                    val host = si.host?.hostAddress ?: return
                    found[si.serviceName] = DiscoveredServer(si.serviceName, host, si.port)
                    emit()
                }
            }
            try { nsdManager.resolveService(info, cb) } catch (e: Exception) {
                Log.w(TAG, "resolve error: ${e.message}")
            }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.contains("droidserve")) resolve(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                found.remove(info.serviceName); emit()
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "discover start error: ${e.message}")
        }

        awaitClose {
            try { nsdManager.stopServiceDiscovery(listener) } catch (_: Exception) {}
        }
    }
}
