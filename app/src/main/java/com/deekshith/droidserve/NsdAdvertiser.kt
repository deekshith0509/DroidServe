package com.deekshith.droidserve

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Advertises the running HTTP server on the local network via mDNS / DNS-SD (NSD).
 *
 * Native clients — notably the DroidServe Android TV app — browse for the service type
 * "_droidserve._tcp" and get the phone's IP + port with zero manual typing. This is the
 * whole point of the TV companion: no more entering "http://192.168.x.x:8080" on a remote.
 *
 * The service name embeds the user-chosen title so multiple phones on one network stay
 * distinguishable in the TV app's picker.
 */
class NsdAdvertiser(context: Context) {

    companion object {
        private const val TAG = "DroidServe"
        const val SERVICE_TYPE = "_droidserve._tcp"
    }

    private val nsdManager = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var listener: NsdManager.RegistrationListener? = null

    fun register(title: String, port: Int) {
        val mgr = nsdManager ?: return
        // Service names must be <= 63 bytes and unique-ish; NSD auto-resolves conflicts.
        val name = ("DroidServe " + title).take(60)
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) {
                Log.d(TAG, "NSD registered: ${s.serviceName}")
            }
            override fun onRegistrationFailed(s: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed: $errorCode")
            }
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, errorCode: Int) {}
        }
        listener = l
        try { mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
        catch (e: Exception) { Log.w(TAG, "NSD register error: ${e.message}") }
    }

    fun unregister() {
        val mgr = nsdManager ?: return
        val l = listener ?: return
        listener = null
        try { mgr.unregisterService(l) } catch (_: Exception) {}
    }
}
