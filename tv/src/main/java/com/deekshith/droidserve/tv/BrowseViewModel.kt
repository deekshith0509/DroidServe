package com.deekshith.droidserve.tv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SortMode { DEFAULT, NAME, SIZE }

/** The screen the UI is currently showing. */
sealed interface UiScreen {
    data class Discovery(
        val servers: List<DiscoveredServer> = emptyList(),
        val error: String? = null,
        val connecting: Boolean = false
    ) : UiScreen

    /** Password prompt for a protected server. */
    data class Auth(
        val server: DiscoveredServer,
        val error: String? = null
    ) : UiScreen

    data class Browse(
        val server: DiscoveredServer,
        val path: String,
        val entries: List<RemoteEntry>,   // already filtered + sorted for display
        val total: Int,                   // total entries before filtering
        val fileCount: Int,
        val dirCount: Int,
        val totalBytes: Long,
        val filter: String = "",
        val sort: SortMode = SortMode.DEFAULT,
        val loading: Boolean = false,
        val error: String? = null
    ) : UiScreen

    data class Play(val server: DiscoveredServer, val entry: RemoteEntry, val returnPath: String) : UiScreen
    data class ViewImage(val entry: RemoteEntry, val returnPath: String, val server: DiscoveredServer) : UiScreen
    data class ViewText(
        val entry: RemoteEntry, val returnPath: String, val server: DiscoveredServer,
        val content: String? = null, val loading: Boolean = true, val error: String? = null
    ) : UiScreen
}

class BrowseViewModel(app: Application) : AndroidViewModel(app) {

    private val discovery = ServerDiscovery(app)

    private val _screen = MutableStateFlow<UiScreen>(UiScreen.Discovery())
    val screen: StateFlow<UiScreen> = _screen.asStateFlow()

    private var client: DroidServeClient? = null
    // Raw (unfiltered/unsorted) listing for the current folder, so filter/sort are instant.
    private var rawEntries: List<RemoteEntry> = emptyList()

    init {
        viewModelScope.launch {
            discovery.discover().collect { servers ->
                (_screen.value as? UiScreen.Discovery)?.let {
                    _screen.value = it.copy(servers = servers)
                }
            }
        }
    }

    /** Connect to a discovered/manual server; probes /api/info to detect auth. */
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
                    openFolder(server, "")
                }
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

    /** Submit credentials for a protected server. */
    fun submitAuth(server: DiscoveredServer, username: String, password: String) {
        viewModelScope.launch {
            try {
                val c = DroidServeClient(server.baseUrl, username, password)
                withContext(Dispatchers.IO) { c.listDir("") }   // validates creds
                client = c
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
                    (client ?: DroidServeClient(server.baseUrl).also { client = it }).listDir(path)
                }
                rawEntries = listing.entries
                _screen.value = render(server, path, filter = "", sort = SortMode.DEFAULT)
            } catch (e: Exception) {
                _screen.value = UiScreen.Browse(
                    server, path, emptyList(), 0, 0, 0, 0,
                    error = e.message ?: "Failed to load"
                )
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

    // Apply filter + sort to the raw listing (folders always grouped first, matching the web UI).
    private fun render(server: DiscoveredServer, path: String, filter: String, sort: SortMode): UiScreen.Browse {
        val f = filter.trim().lowercase()
        var shown = if (f.isEmpty()) rawEntries
                    else rawEntries.filter { it.name.lowercase().contains(f) }
        shown = when (sort) {
            SortMode.DEFAULT -> shown  // server already returns dirs-first, alpha
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

    fun onEntryClick(entry: RemoteEntry) {
        val cur = _screen.value as? UiScreen.Browse ?: return
        when {
            entry.isDir -> {
                val childPath = if (cur.path.isEmpty()) entry.name else "${cur.path}/${entry.name}"
                openFolder(cur.server, childPath)
            }
            entry.isPlayable -> _screen.value = UiScreen.Play(cur.server, entry, cur.path)
            entry.isImage -> _screen.value = UiScreen.ViewImage(entry, cur.path, cur.server)
            entry.isText -> loadText(cur.server, entry, cur.path)
            // Unknown types: no native viewer — ignore (a download action could be added).
        }
    }

    private fun loadText(server: DiscoveredServer, entry: RemoteEntry, returnPath: String) {
        _screen.value = UiScreen.ViewText(entry, returnPath, server, loading = true)
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    (client ?: DroidServeClient(server.baseUrl)).fetchText(entry.url)
                }
                _screen.value = UiScreen.ViewText(entry, returnPath, server, content = text, loading = false)
            } catch (e: Exception) {
                _screen.value = UiScreen.ViewText(entry, returnPath, server, loading = false, error = e.message)
            }
        }
    }

    /** Back navigation. Returns true if handled, false if the app should exit. */
    fun onBack(): Boolean {
        when (val cur = _screen.value) {
            is UiScreen.Play -> { openFolder(cur.server, cur.returnPath); return true }
            is UiScreen.ViewImage -> { openFolder(cur.server, cur.returnPath); return true }
            is UiScreen.ViewText -> { openFolder(cur.server, cur.returnPath); return true }
            is UiScreen.Auth -> { _screen.value = UiScreen.Discovery(); return true }
            is UiScreen.Browse -> {
                if (cur.path.isEmpty()) { _screen.value = UiScreen.Discovery(); return true }
                openFolder(cur.server, cur.path.substringBeforeLast('/', ""))
                return true
            }
            is UiScreen.Discovery -> return false
        }
    }
}
