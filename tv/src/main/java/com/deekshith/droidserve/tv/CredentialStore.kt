package com.deekshith.droidserve.tv

import android.content.Context

/**
 * Remembers the last successful credentials per server so a returning user isn't forced to
 * re-type a password on a remote. Keyed by "host:port".
 *
 * Storage note: this is a trusted-LAN, personal-use app and the server speaks plain HTTP
 * (credentials already travel in cleartext on the wire), so credentials are kept in private
 * SharedPreferences. They are only ever sent back to the same server they came from.
 */
class CredentialStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("droidserve_tv_creds", Context.MODE_PRIVATE)

    data class Saved(val username: String, val password: String)

    private fun key(host: String, port: Int) = "$host:$port"

    fun save(host: String, port: Int, username: String, password: String) {
        if (password.isEmpty()) return
        prefs.edit()
            .putString("${key(host, port)}::u", username)
            .putString("${key(host, port)}::p", password)
            .apply()
    }

    /** Returns the saved credentials for this server, or null if none stored. */
    fun get(host: String, port: Int): Saved? {
        val p = prefs.getString("${key(host, port)}::p", null) ?: return null
        val u = prefs.getString("${key(host, port)}::u", "") ?: ""
        return Saved(u, p)
    }

    fun has(host: String, port: Int): Boolean = get(host, port) != null

    fun forget(host: String, port: Int) {
        prefs.edit()
            .remove("${key(host, port)}::u")
            .remove("${key(host, port)}::p")
            .apply()
    }
}
