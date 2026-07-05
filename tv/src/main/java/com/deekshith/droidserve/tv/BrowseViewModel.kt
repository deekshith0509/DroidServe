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

/** The screen the UI is currently showing. */
sealed interface UiScreen {
    /** Picking a server: live-discovered list + optional manual entry. */
    data class Discovery(
        val servers: List<DiscoveredServer> = emptyList(),
        val manualError: String? = null
    ) : UiScreen

    /** Browsing a folder on the connected server. */
    data class Browse(
        val server: DiscoveredServer,
        val path: String,
        val entries: List<RemoteEntry>,
        val loading: Boolean = false,
        val error: String? = null
    ) : UiScreen

    /** Playing a media file. */
    data class Play(
        val server: DiscoveredServer,
        val entry: RemoteEntry,
        val returnPath: String
    ) : UiScreen
}

class BrowseViewModel(app: Application) : AndroidViewModel(app) {

    private val discovery = ServerDiscovery(app)

    private val _screen = MutableStateFlow<UiScreen>(UiScreen.Discovery())
    val screen: StateFlow<UiScreen> = _screen.asStateFlow()

    private var client: DroidServeClient? = null

    init {
        // Continuously feed discovered servers into the Discovery screen.
        viewModelScope.launch {
            discovery.discover().collect { servers ->
                val cur = _screen.value
                if (cur is UiScreen.Discovery) {
                    _screen.value = cur.copy(servers = servers)
                }
            }
        }
    }

    fun connect(server: DiscoveredServer) {
        client = DroidServeClient(server.baseUrl)
        openFolder(server, "")
    }

    /** Manual fallback: user typed host[:port]. */
    fun connectManual(input: String) {
        val trimmed = input.trim().removePrefix("http://").removeSuffix("/")
        if (trimmed.isEmpty()) return
        val host = trimmed.substringBefore(':')
        val port = trimmed.substringAfter(':', "8080").toIntOrNull() ?: 8080
        connect(DiscoveredServer("$host:$port", host, port))
    }

    fun openFolder(server: DiscoveredServer, path: String) {
        _screen.value = UiScreen.Browse(server, path, emptyList(), loading = true)
        viewModelScope.launch {
            try {
                val listing = withContext(Dispatchers.IO) {
                    (client ?: DroidServeClient(server.baseUrl).also { client = it }).listDir(path)
                }
                _screen.value = UiScreen.Browse(server, path, listing.entries)
            } catch (e: Exception) {
                _screen.value = UiScreen.Browse(
                    server, path, emptyList(), error = e.message ?: "Failed to load"
                )
            }
        }
    }

    fun onEntryClick(entry: RemoteEntry) {
        val cur = _screen.value as? UiScreen.Browse ?: return
        if (entry.isDir) {
            val childPath = if (cur.path.isEmpty()) entry.name else "${cur.path}/${entry.name}"
            openFolder(cur.server, childPath)
        } else if (entry.isPlayable) {
            _screen.value = UiScreen.Play(cur.server, entry, cur.path)
        }
        // Images / other files are ignored on TV for now (no viewer yet).
    }

    /** Back navigation. Returns true if handled, false if the app should exit. */
    fun onBack(): Boolean {
        when (val cur = _screen.value) {
            is UiScreen.Play -> {
                openFolder(cur.server, cur.returnPath)
                return true
            }
            is UiScreen.Browse -> {
                if (cur.path.isEmpty()) {
                    _screen.value = UiScreen.Discovery()
                    return true
                }
                val parent = cur.path.substringBeforeLast('/', "")
                openFolder(cur.server, parent)
                return true
            }
            is UiScreen.Discovery -> return false
        }
    }
}
