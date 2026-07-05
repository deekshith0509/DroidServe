package com.deekshith.droidserve.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Discovers DroidServe phone servers advertised via mDNS/NSD ("_droidserve._tcp").
 *
 * Emits the full set of currently-known servers on every change, so the UI shows a live
 * picker with no manual IP typing. Multiple phones on one LAN all appear here.
 *
 * Two things are essential for reliable discovery and were the cause of "nothing shows up":
 *  1. A Wi-Fi MulticastLock — without it many devices silently drop inbound mDNS multicast.
 *  2. Serialized resolves — NsdManager only handles one resolveService() at a time on older
 *     APIs; firing several in parallel makes them fail with FAILURE_ALREADY_ACTIVE. We queue
 *     them and resolve one at a time, retrying on that specific error.
 */
class ServerDiscovery(context: Context) {

    companion object {
        private const val TAG = "DroidServeTv"
        private const val SERVICE_TYPE = "_droidserve._tcp."
    }

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    fun discover(): Flow<List<DiscoveredServer>> = callbackFlow {
        val mgr = nsdManager
        if (mgr == null) { awaitClose { }; return@callbackFlow }
        val found = LinkedHashMap<String, DiscoveredServer>()
        fun emit() { trySend(found.values.toList()) }

        // Hold multicast so inbound mDNS packets are delivered to us.
        val multicastLock = wifi?.createMulticastLock("droidserve-tv-nsd")?.apply {
            setReferenceCounted(true)
            try { acquire() } catch (_: Exception) {}
        }

        // Serialize resolves: only one in flight at a time.
        val resolveQueue = ArrayDeque<NsdServiceInfo>()
        val resolving = AtomicBoolean(false)

        fun pumpResolves() {
            if (resolving.get()) return
            val next = synchronized(resolveQueue) { resolveQueue.removeFirstOrNull() } ?: return
            resolving.set(true)
            val cb = object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                    // Busy — requeue this one and try again shortly.
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                        synchronized(resolveQueue) { resolveQueue.addLast(si) }
                    } else {
                        Log.w(TAG, "resolve failed for ${si.serviceName}: $errorCode")
                    }
                    resolving.set(false)
                    pumpResolves()
                }
                override fun onServiceResolved(si: NsdServiceInfo) {
                    val host = si.host?.hostAddress
                    if (host != null) {
                        found[si.serviceName] = DiscoveredServer(si.serviceName, host, si.port)
                        emit()
                    }
                    resolving.set(false)
                    pumpResolves()
                }
            }
            try { mgr.resolveService(next, cb) }
            catch (e: Exception) {
                Log.w(TAG, "resolve error: ${e.message}")
                resolving.set(false)
                pumpResolves()
            }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String, e: Int) { Log.w(TAG, "start discovery failed: $e") }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onDiscoveryStarted(t: String) { Log.d(TAG, "discovery started") }
            override fun onDiscoveryStopped(t: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.contains("droidserve")) {
                    synchronized(resolveQueue) { resolveQueue.addLast(info) }
                    pumpResolves()
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                found.remove(info.serviceName); emit()
            }
        }

        try {
            mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "discover start error: ${e.message}")
        }

        awaitClose {
            try { mgr.stopServiceDiscovery(listener) } catch (_: Exception) {}
            try { if (multicastLock?.isHeld == true) multicastLock.release() } catch (_: Exception) {}
        }
    }
}
