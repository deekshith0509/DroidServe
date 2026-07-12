package com.deekshith.droidserve.tv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SortMode { DEFAULT, NAME, SIZE }

/** A media stream to hand to the OS default player (VLC/MX/etc) via ACTION_VIEW. */
data class OpenRequest(val url: String, val mime: String, val subUrl: String? = null)

/** The screen the UI is currently showing. */
sealed interface UiScreen {
    data class Discovery(
        val servers: List<DiscoveredServer> = emptyList(),
        val error: String? = null,
        val connecting: Boolean = false,
        val scanning: Boolean = false
    ) : UiScreen

    /** Password prompt for a protected server. */
    data class Auth(val server: DiscoveredServer, val error: String? = null) : UiScreen

    data class Browse(
        val server: DiscoveredServer,
        val path: String,
        val entries: List<RemoteEntry>,   // filtered + sorted for display
        val total: Int,
        val fileCount: Int,
        val dirCount: Int,
        val totalBytes: Long,
        val filter: String = "",
        val sort: SortMode = SortMode.DEFAULT,
        val loading: Boolean = false,
        val error: String? = null,
        val casting: OpenRequest? = null   // transient toast when a phone casts to us
    ) : UiScreen
}

class BrowseViewModel(app: Application) : AndroidViewModel(app) {

    private val discovery = ServerDiscovery(app)
    private val scanner = SubnetScanner(app)

    private val _screen = MutableStateFlow<UiScreen>(UiScreen.Discovery())
    val screen: StateFlow<UiScreen> = _screen.asStateFlow()

    // One-shot events telling the Activity to fire an ACTION_VIEW intent (open in native player).
    private val _open = MutableSharedFlow<OpenRequest>(extraBufferCapacity = 4)
    val open: SharedFlow<OpenRequest> = _open.asSharedFlow()

    private var client: DroidServeClient? = null
    private var rawEntries: List<RemoteEntry> = emptyList()
    private var pollJob: Job? = null
    private var connectedBase: String? = null
    private var connectedCreds: Pair<String, String?>? = null

    // Merged results keyed by "host:port" so mDNS + subnet-probe fallback never duplicate.
    private val discovered = LinkedHashMap<String, DiscoveredServer>()
    private var scanJob: Job? = null

    init {
        // Primary: live mDNS/NSD — every phone advertising _droidserve._tcp appears here,
        // at near-zero cost (no polling, no sockets). This is the whole discovery story on
        // networks that allow multicast.
        viewModelScope.launch {
            discovery.discover().collect { servers ->
                servers.forEach { discovered["${it.host}:${it.port}"] = it }
                publish()
            }
        }
        // Fallback: ONE subnet probe on launch, for networks that block mDNS multicast so a
        // reachable phone would otherwise never appear. Deliberately not a loop — continuous
        // scanning saturates a weak TV's single Wi-Fi NIC and starves other tools (adb /
        // atvtools). The user can re-scan on demand from the picker.
        scanOnce()
    }

    /** Run a single polite subnet sweep. No-op if one is already in flight. */
    fun scanOnce() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            (_screen.value as? UiScreen.Discovery)?.let { _screen.value = it.copy(scanning = true) }
            try {
                val found = withContext(Dispatchers.IO) { scanner.scan() }
                found.forEach { discovered["${it.host}:${it.port}"] = it }
            } catch (_: Exception) {}
            (_screen.value as? UiScreen.Discovery)?.let {
                _screen.value = it.copy(servers = discovered.values.toList(), scanning = false)
            }
        }
    }

    /** User-triggered rescan from the picker. */
    fun rescan() = scanOnce()

    private fun publish() {
        (_screen.value as? UiScreen.Discovery)?.let { cur ->
            _screen.value = cur.copy(servers = discovered.values.toList())
        }
    }

    fun connect(server: DiscoveredServer) {
        _screen.value = UiScreen.Discovery(
            servers = (_screen.value as? UiScreen.Discovery)?.servers ?: emptyList(),
            connecting = true
        )
        viewModelScope.launch {
            try {
                val probe = DroidServeClient(server.baseUrl)
                val info = withContext(Dispatchers.IO) { probe.fetchInfo() }
                if (info.auth) {
                    _screen.value = UiScreen.Auth(server)
                } else {
                    client = probe
                    connectedBase = server.baseUrl
                    connectedCreds = "" to null
                    startPollLoop()
                    openFolder(server, "")
                }
            } catch (e: AuthException) {
                // A properly-secured server requires credentials even for /api/info, so the
                // probe itself 401s. That is NOT an error — it means "log in", so route to the
                // password prompt instead of showing an "unreachable" message.
                _screen.value = UiScreen.Auth(server)
            } catch (e: Exception) {
                _screen.value = UiScreen.Discovery(
                    servers = (_screen.value as? UiScreen.Discovery)?.servers ?: emptyList(),
                    error = "Could not reach ${server.host}:${server.port} — ${e.message}"
                )
            }
        }
    }

    fun connectManual(host: String, port: Int) =
        connect(DiscoveredServer("$host:$port", host, port))

    fun submitAuth(server: DiscoveredServer, username: String, password: String) {
        viewModelScope.launch {
            try {
                val c = DroidServeClient(server.baseUrl, username, password)
                withContext(Dispatchers.IO) { c.listDir("") }
                client = c
                connectedBase = server.baseUrl
                connectedCreds = username to password
                startPollLoop()
                openFolder(server, "")
            } catch (e: AuthException) {
                _screen.value = UiScreen.Auth(server, error = "Wrong password, try again")
            } catch (e: Exception) {
                _screen.value = UiScreen.Auth(server, error = e.message ?: "Failed")
            }
        }
    }

    fun openFolder(server: DiscoveredServer, path: String) {
        _screen.value = UiScreen.Browse(server, path, emptyList(), 0, 0, 0, 0, loading = true)
        viewModelScope.launch {
            try {
                val listing = withContext(Dispatchers.IO) {
                    // Reuse the authenticated client from connect()/submitAuth(). The fallback
                    // rebuilds it from the stored creds (not a bare, credential-less client) so a
                    // protected server never 401s just because `client` was lost.
                    val c = client ?: run {
                        val (u, p) = connectedCreds ?: ("" to null)
                        DroidServeClient(connectedBase ?: server.baseUrl, u, p).also { client = it }
                    }
                    c.listDir(path)
                }
                rawEntries = listing.entries
                _screen.value = render(server, path, "", SortMode.DEFAULT)
            } catch (e: AuthException) {
                // Credentials went stale (server restarted with a new token) — re-prompt.
                _screen.value = UiScreen.Auth(server, error = "Session expired, sign in again")
            } catch (e: Exception) {
                _screen.value = UiScreen.Browse(server, path, emptyList(), 0, 0, 0, 0,
                    error = e.message ?: "Failed to load")
            }
        }
    }

    fun setFilter(text: String) {
        val cur = _screen.value as? UiScreen.Browse ?: return
        _screen.value = render(cur.server, cur.path, text, cur.sort)
    }

    fun setSort(mode: SortMode) {
        val cur = _screen.value as? UiScreen.Browse ?: return
        _screen.value = render(cur.server, cur.path, cur.filter, mode)
    }

    private fun render(server: DiscoveredServer, path: String, filter: String, sort: SortMode): UiScreen.Browse {
        val f = filter.trim().lowercase()
        var shown = if (f.isEmpty()) rawEntries else rawEntries.filter { it.name.lowercase().contains(f) }
        shown = when (sort) {
            SortMode.DEFAULT -> shown
            SortMode.NAME -> shown.sortedWith(compareBy({ if (it.isDir) 0 else 1 }, { it.name.lowercase() }))
            SortMode.SIZE -> shown.sortedWith(compareBy({ if (it.isDir) 0 else 1 }, { -it.size }))
        }
        return UiScreen.Browse(
            server = server, path = path, entries = shown,
            total = rawEntries.size,
            fileCount = rawEntries.count { !it.isDir },
            dirCount = rawEntries.count { it.isDir },
            totalBytes = rawEntries.filter { !it.isDir }.sumOf { it.size },
            filter = filter, sort = sort
        )
    }

    /** Click a row: folders navigate; files open in the OS default app (native player/viewer). */
    fun onEntryClick(entry: RemoteEntry) {
        val cur = _screen.value as? UiScreen.Browse ?: return
        if (entry.isDir) {
            val childPath = if (cur.path.isEmpty()) entry.name else "${cur.path}/${entry.name}"
            openFolder(cur.server, childPath)
        } else {
            // Delegate to the device's own capable player/viewer — no built-in player.
            _open.tryEmit(OpenRequest(entry.url, entry.mime, entry.subUrl))
        }
    }

    // ── Real-time cast: long-poll the phone; when it casts, open in the native player ──
    private fun startPollLoop() {
        pollJob?.cancel()
        val base = connectedBase ?: return
        val (user, pass) = connectedCreds ?: ("" to null)
        pollJob = viewModelScope.launch {
            val poller = DroidServeClient(base, user, pass)
            while (isActive) {
                try {
                    val cmd = withContext(Dispatchers.IO) { poller.pollCast() }
                    if (cmd != null) {
                        val req = OpenRequest(cmd.url, cmd.mime, cmd.subUrl)
                        _open.tryEmit(req)
                        (_screen.value as? UiScreen.Browse)?.let {
                            _screen.value = it.copy(casting = req)
                        }
                    }
                } catch (e: Exception) {
                    // Server asleep/unreachable — back off, then keep trying (self-heal).
                    kotlinx.coroutines.delay(3000)
                }
            }
        }
    }

    fun clearCastToast() {
        (_screen.value as? UiScreen.Browse)?.let {
            if (it.casting != null) _screen.value = it.copy(casting = null)
        }
    }

    fun onBack(): Boolean {
        when (val cur = _screen.value) {
            is UiScreen.Auth -> { _screen.value = backToDiscovery(); return true }
            is UiScreen.Browse -> {
                if (cur.path.isEmpty()) {
                    pollJob?.cancel()
                    _screen.value = backToDiscovery()
                    return true
                }
                openFolder(cur.server, cur.path.substringBeforeLast('/', ""))
                return true
            }
            is UiScreen.Discovery -> return false
        }
    }

    // Returning to the picker must show the servers we already know about, not an empty list
    // (mDNS won't necessarily re-announce immediately, and we don't auto-rescan on every back).
    private fun backToDiscovery() = UiScreen.Discovery(servers = discovered.values.toList())

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }
}
