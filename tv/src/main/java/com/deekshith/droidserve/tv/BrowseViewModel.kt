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

enum class ViewerKind { TEXT, IMAGE }

enum class ApkPhase { DOWNLOADING, READY, FAILED }

/** A media stream to hand to the OS default player (VLC/MX/etc) via ACTION_VIEW. */
data class OpenRequest(
    val url: String,
    val mime: String,
    val subUrl: String? = null,
    /** Resume position in ms to hand the external player, or 0 to start from the beginning. */
    val resumeMs: Int = 0,
    /** Always show the "Open with" chooser (used for pasted links so the user picks their player). */
    val forceChooser: Boolean = false
)

/** The screen the UI is currently showing. */
sealed interface UiScreen {
    data class Discovery(
        val servers: List<DiscoveredServer> = emptyList(),
        val error: String? = null,
        val connecting: Boolean = false,
        val scanning: Boolean = false
    ) : UiScreen

    /**
     * Password prompt for a protected server.
     * @param savedUsername/savedPassword non-null when we remember a prior successful login for
     *   this server, so the UI can offer "Use saved password" instead of forcing a re-type.
     */
    data class Auth(
        val server: DiscoveredServer,
        val error: String? = null,
        val savedUsername: String? = null,
        val savedPassword: String? = null
    ) : UiScreen {
        val hasSaved: Boolean get() = savedPassword != null
    }

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
        val casting: OpenRequest? = null,  // transient toast when a phone casts to us
        val gridMode: Boolean = true       // poster grid vs list in the browse screen
    ) : UiScreen

    /**
     * In-app viewer for text and images. Rendering these in-app (instead of firing an external
     * ACTION_VIEW) means a TV with no text/image app — or a buggy one — never breaks the flow,
     * and there's no jarring app switch on a 10-foot UI. Only video delegates to a native
     * player, where hardware decoding actually matters.
     */
    data class Viewer(
        val server: DiscoveredServer,
        val name: String,
        val kind: ViewerKind,
        val imageUrl: String? = null,      // for images
        val text: String? = null,          // for text (loaded)
        val loading: Boolean = false,
        val error: String? = null
    ) : UiScreen

    /**
     * In-app audio player. Audio decoding is cheap and MediaPlayer handles mp3/aac/ogg/wav/flac
     * natively on every Android version, so we play songs in-app rather than handing them to an
     * external app (which may be missing or buggy). [queue] is the folder's audio files so the
     * user gets prev/next; [index] is the currently-playing track.
     */
    data class AudioPlayer(
        val server: DiscoveredServer,
        val queue: List<RemoteEntry>,
        val index: Int
    ) : UiScreen {
        val current: RemoteEntry get() = queue[index]
        val hasPrev: Boolean get() = index > 0
        val hasNext: Boolean get() = index < queue.lastIndex
    }

    /** Downloading an APK from the phone, then handing it to the system installer (no ADB). */
    data class ApkInstall(
        val server: DiscoveredServer,
        val name: String,
        val phase: ApkPhase,
        val progress: Int = 0,
        val error: String? = null
    ) : UiScreen

    /** Recursive, folder-grouped music library (albums grid, searchable). */
    data class MusicLibrary(
        val server: DiscoveredServer,
        val albums: List<MusicAlbum>,
        val loading: Boolean = false,
        val error: String? = null,
        val filter: String = ""
    ) : UiScreen {
        val shown: List<MusicAlbum> get() =
            if (filter.isBlank()) albums else filter.trim().lowercase().let { f ->
                albums.filter { it.name.lowercase().contains(f) || it.songs.any { s -> s.name.lowercase().contains(f) } }
            }
    }

    /** One album's song list. */
    data class AlbumSongs(
        val server: DiscoveredServer,
        val album: MusicAlbum
    ) : UiScreen

    /**
     * Streaming links the phone pushed. Updated live via long-poll; the user picks one with the
     * remote to open in an external player. We never auto-launch — the list is the whole point.
     */
    data class Links(
        val server: DiscoveredServer,
        val links: List<StreamLink>,
        val loading: Boolean = false,
        val error: String? = null
    ) : UiScreen

    /** Channels of one auto-expanded playlist group, drilled into from [Links]. */
    data class LinkGroup(
        val server: DiscoveredServer,
        val group: String,
        val links: List<StreamLink>
    ) : UiScreen
}

class BrowseViewModel(app: Application) : AndroidViewModel(app) {

    private val discovery = ServerDiscovery(app)
    private val scanner = SubnetScanner(app)
    private val creds = CredentialStore(app)
    private val playback = PlaybackStore(app)
    private val prefs = app.getSharedPreferences("ds_tv", android.content.Context.MODE_PRIVATE)
    private var gridMode = prefs.getBoolean("grid", true)

    private val _screen = MutableStateFlow<UiScreen>(UiScreen.Discovery())
    val screen: StateFlow<UiScreen> = _screen.asStateFlow()

    // One-shot events telling the Activity to fire an ACTION_VIEW intent (open in native player).
    private val _open = MutableSharedFlow<OpenRequest>(extraBufferCapacity = 4)
    val open: SharedFlow<OpenRequest> = _open.asSharedFlow()

    // One-shot events telling the Activity to launch the system package installer on a local APK.
    private val _install = MutableSharedFlow<java.io.File>(extraBufferCapacity = 2)
    val install: SharedFlow<java.io.File> = _install.asSharedFlow()
    private var lastApkFile: java.io.File? = null

    private var musicAlbums: List<MusicAlbum> = emptyList()
    private var musicContext: MusicAlbum? = null   // set when a song is played from the music UI

    private var client: DroidServeClient? = null
    private var rawEntries: List<RemoteEntry> = emptyList()
    private var currentPath: String = ""     // last opened folder, so the viewer can return to it
    private var pollJob: Job? = null
    private var linksJob: Job? = null
    private var streamLinks: List<StreamLink> = emptyList()
    private var linkVersion = -1
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
                val enriched = server.copy(device = info.device)
                if (info.auth) {
                    promptOrUseSaved(enriched)
                } else {
                    client = probe
                    connectedBase = server.baseUrl
                    connectedCreds = "" to null
                    startPollLoop()
                    openFolder(enriched, "")
                }
            } catch (e: AuthException) {
                // A properly-secured server requires credentials even for /api/info, so the
                // probe itself 401s. That is NOT an error — it means "log in". If we already
                // remember a working password for this server, try it automatically; otherwise
                // show the prompt (pre-filled with any saved value).
                promptOrUseSaved(server)
            } catch (e: Exception) {
                _screen.value = UiScreen.Discovery(
                    servers = (_screen.value as? UiScreen.Discovery)?.servers ?: emptyList(),
                    error = "Could not reach ${server.host}:${server.port} — ${e.message}"
                )
            }
        }
    }

    // Decide what to show for a protected server: silently reuse a remembered password when we
    // have one (so a returning user isn't asked again), else present the password prompt.
    private fun promptOrUseSaved(server: DiscoveredServer) {
        val saved = creds.get(server.host, server.port)
        if (saved != null) {
            // Try the saved password automatically first.
            attemptAuth(server, saved.username, saved.password, remember = true, fromSaved = true)
        } else {
            _screen.value = UiScreen.Auth(server)
        }
    }

    fun connectManual(host: String, port: Int) =
        connect(DiscoveredServer("$host:$port", host, port))

    /** Called from the Auth screen's Connect button. [remember] persists creds on success. */
    fun submitAuth(server: DiscoveredServer, username: String, password: String, remember: Boolean = true) =
        attemptAuth(server, username, password, remember, fromSaved = false)

    // Shared auth attempt used by both a fresh login and an automatic saved-password retry.
    // On success: opens the server (and optionally remembers the credentials). On failure:
    // shows the password prompt. When a *saved* password fails (server password changed), the
    // stale entry is forgotten and the prompt explains why.
    private fun attemptAuth(
        server: DiscoveredServer, username: String, password: String,
        remember: Boolean, fromSaved: Boolean
    ) {
        viewModelScope.launch {
            try {
                val c = DroidServeClient(server.baseUrl, username, password)
                withContext(Dispatchers.IO) { c.listDir("") }
                client = c
                connectedBase = server.baseUrl
                connectedCreds = username to password
                if (remember) creds.save(server.host, server.port, username, password)
                startPollLoop()
                openFolder(server, "")
            } catch (e: AuthException) {
                if (fromSaved) {
                    // The remembered password no longer works (server password changed). Drop it
                    // and ask the user for the new one, pre-filling the old username.
                    creds.forget(server.host, server.port)
                    _screen.value = UiScreen.Auth(server,
                        error = "Saved password no longer works — enter the new one",
                        savedUsername = username)
                } else {
                    _screen.value = UiScreen.Auth(server, error = "Wrong password, try again",
                        savedUsername = username)
                }
            } catch (e: Exception) {
                _screen.value = UiScreen.Auth(server, error = e.message ?: "Failed",
                    savedUsername = username)
            }
        }
    }

    /** Forget the saved password for a server (offered from the Auth screen). */
    fun forgetSaved(server: DiscoveredServer) {
        creds.forget(server.host, server.port)
        _screen.value = UiScreen.Auth(server)
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
                currentPath = path
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
            filter = filter, sort = sort,
            gridMode = gridMode
        )
    }

    fun toggleView() {
        gridMode = !gridMode
        prefs.edit().putBoolean("grid", gridMode).apply()
        (_screen.value as? UiScreen.Browse)?.let { _screen.value = it.copy(gridMode = gridMode) }
    }

    /** Re-fire the install intent for the already-downloaded APK (e.g. after granting unknown sources). */
    fun installReady() { lastApkFile?.let { _install.tryEmit(it) } }

    // ── Music library ─────────────────────────────────────────────────────
    fun openMusic() {
        val server = (_screen.value as? UiScreen.Browse)?.server ?: return
        musicContext = null
        _screen.value = UiScreen.MusicLibrary(server, musicAlbums, loading = musicAlbums.isEmpty())
        viewModelScope.launch {
            try {
                val albums = withContext(Dispatchers.IO) {
                    val c = client ?: run {
                        val (u, p) = connectedCreds ?: ("" to null)
                        DroidServeClient(connectedBase ?: server.baseUrl, u, p)
                    }
                    c.fetchMusic()
                }
                musicAlbums = albums
                (_screen.value as? UiScreen.MusicLibrary)?.let { _screen.value = it.copy(albums = albums, loading = false) }
            } catch (e: Exception) {
                (_screen.value as? UiScreen.MusicLibrary)?.let { _screen.value = it.copy(loading = false, error = e.message ?: "Failed to load music") }
            }
        }
    }

    fun setMusicFilter(text: String) {
        (_screen.value as? UiScreen.MusicLibrary)?.let { _screen.value = it.copy(filter = text) }
    }

    fun openAlbum(album: MusicAlbum) {
        (_screen.value as? UiScreen.MusicLibrary)?.let { _screen.value = UiScreen.AlbumSongs(it.server, album) }
    }

    fun playSong(album: MusicAlbum, index: Int) {
        val server = (_screen.value as? UiScreen.AlbumSongs)?.server ?: return
        musicContext = album
        _screen.value = UiScreen.AudioPlayer(server, album.songs, index)
    }

    // ── Streaming links ────────────────────────────────────────────────────
    fun openLinks() {
        val server = (_screen.value as? UiScreen.Browse)?.server ?: return
        _screen.value = UiScreen.Links(server, streamLinks, loading = streamLinks.isEmpty() && linkVersion < 0)
        // Refresh now so an opened tab is never stale, even if the background poll just missed one.
        viewModelScope.launch {
            try {
                val (v, list) = withContext(Dispatchers.IO) { linksClient().fetchLinks() }
                linkVersion = v; streamLinks = list
                (_screen.value as? UiScreen.Links)?.let { _screen.value = it.copy(links = list, loading = false) }
            } catch (e: Exception) {
                (_screen.value as? UiScreen.Links)?.let { _screen.value = it.copy(loading = false, error = e.message ?: "Failed to load") }
            }
        }
    }

    /**
     * Open a pasted link via the chooser. A resolved direct stream is typed as video/audio so the
     * native players (VLC/MX/Kodi) are offered; a page we couldn't resolve is left untyped so the
     * chooser offers a browser (which runs the page's own JS player) instead of bouncing a player.
     */
    fun onLinkClick(link: StreamLink) {
        val mime = if (link.playable) mimeForUrl(link.url) else ""
        _open.tryEmit(OpenRequest(link.url, mime, forceChooser = true))
    }

    // Known media extensions get a typed intent (so players register as handlers); everything else
    // (magnet, web pages, third-party stream endpoints) is opened untyped so the OS resolves it by
    // scheme and offers whatever can handle it.
    private fun mimeForUrl(url: String): String {
        if (url.startsWith("magnet:", ignoreCase = true)) return ""   // torrent apps match the scheme, not a MIME
        val u = url.substringBefore('#').substringBefore('?').lowercase()
        // Streaming / movie links are the common case, so default to video/*. An UNTYPED http URL
        // only matches browsers (Silk); video/* is what VLC / MX / Kodi register for, so they get
        // offered. Audio files are typed audio/*; magnet stays untyped for torrent clients.
        return if (Regex("\\.(mp3|flac|aac|ogg|wav|m4a|opus)$").containsMatchIn(u)) "audio/*" else "video/*"
    }

    private fun linksClient(): DroidServeClient {
        val (u, p) = connectedCreds ?: ("" to null)
        return client ?: DroidServeClient(connectedBase ?: "", u, p)
    }

    // Keep the links list fresh in the background so opening the tab is instant and updates arrive
    // live. Runs whenever connected (alongside the cast poll); self-heals on transient failures.
    private fun startLinksPoll() {
        linksJob?.cancel()
        val base = connectedBase ?: return
        val (user, pass) = connectedCreds ?: ("" to null)
        linksJob = viewModelScope.launch {
            val c = DroidServeClient(base, user, pass)
            try {
                val (v, list) = withContext(Dispatchers.IO) { c.fetchLinks() }
                linkVersion = v; streamLinks = list; pushLinks()
            } catch (_: Exception) {}
            while (isActive) {
                try {
                    val res = withContext(Dispatchers.IO) { c.pollLinks(linkVersion) }
                    if (res != null) { linkVersion = res.first; streamLinks = res.second; pushLinks() }
                } catch (e: Exception) {
                    kotlinx.coroutines.delay(3000)
                }
            }
        }
    }

    /** Drill into one playlist group's channels. Called from the Links screen. */
    fun openLinkGroup(group: String) {
        val server = (_screen.value as? UiScreen.Links)?.server ?: return
        _screen.value = UiScreen.LinkGroup(server, group, streamLinks.filter { it.group == group })
    }

    private fun pushLinks() {
        when (val s = _screen.value) {
            is UiScreen.Links -> _screen.value = s.copy(links = streamLinks, loading = false)
            is UiScreen.LinkGroup -> _screen.value = s.copy(links = streamLinks.filter { it.group == s.group })
            else -> {}
        }
    }

    /**
     * Click a row: folders navigate; text & images open in the IN-APP viewer (robust, no app
     * switch, works even if the TV has no/only-broken text/image apps); video & audio delegate
     * to the device's native player (where hardware decoding matters).
     */
    fun onEntryClick(entry: RemoteEntry) {
        val cur = _screen.value as? UiScreen.Browse ?: return
        when {
            entry.isDir -> {
                val childPath = if (cur.path.isEmpty()) entry.name else "${cur.path}/${entry.name}"
                openFolder(cur.server, childPath)
            }
            entry.isImage -> {
                _screen.value = UiScreen.Viewer(cur.server, entry.name, ViewerKind.IMAGE, imageUrl = entry.url)
            }
            entry.isText -> {
                _screen.value = UiScreen.Viewer(cur.server, entry.name, ViewerKind.TEXT, loading = true)
                viewModelScope.launch {
                    try {
                        val body = withContext(Dispatchers.IO) {
                            val c = client ?: run {
                                val (u, p) = connectedCreds ?: ("" to null)
                                DroidServeClient(connectedBase ?: cur.server.baseUrl, u, p)
                            }
                            c.fetchText(entry.url)
                        }
                        (_screen.value as? UiScreen.Viewer)?.let {
                            if (it.name == entry.name) _screen.value = it.copy(text = body, loading = false)
                        }
                    } catch (e: Exception) {
                        (_screen.value as? UiScreen.Viewer)?.let {
                            if (it.name == entry.name) _screen.value = it.copy(error = e.message ?: "Failed to load", loading = false)
                        }
                    }
                }
            }
            entry.isAudio -> {
                // In-app audio player with the folder's other songs as a prev/next queue.
                musicContext = null
                val queue = rawEntries.filter { it.isAudio }
                val idx = queue.indexOfFirst { it.name == entry.name }.coerceAtLeast(0)
                _screen.value = UiScreen.AudioPlayer(cur.server, queue, idx)
            }
            entry.isApk -> downloadAndInstallApk(cur.server, entry)
            else -> {
                // Video (or anything else) → native player via ACTION_VIEW, where hardware
                // decoding and broad codec support matter. Hand it the last resume position so
                // playback continues where it stopped.
                _open.tryEmit(OpenRequest(entry.url, entry.mime, entry.subUrl, playback.get(entry.url)))
            }
        }
    }

    // Pull an APK local (the system installer cannot read a remote URL), stream with progress,
    // then emit an install event the Activity turns into a FileProvider ACTION_VIEW install intent.
    private fun downloadAndInstallApk(server: DiscoveredServer, entry: RemoteEntry) {
        _screen.value = UiScreen.ApkInstall(server, entry.name, ApkPhase.DOWNLOADING, 0)
        viewModelScope.launch {
            try {
                val dir = java.io.File(getApplication<Application>().cacheDir, "apk")
                val dest = java.io.File(dir, entry.name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
                withContext(Dispatchers.IO) {
                    val c = client ?: run {
                        val (u, p) = connectedCreds ?: ("" to null)
                        DroidServeClient(connectedBase ?: server.baseUrl, u, p)
                    }
                    c.downloadTo(entry.url, dest) { done, total ->
                        val pct = if (total > 0) ((done * 100) / total).toInt() else 0
                        (_screen.value as? UiScreen.ApkInstall)?.let {
                            if (it.name == entry.name && it.progress != pct) _screen.value = it.copy(progress = pct)
                        }
                    }
                }
                lastApkFile = dest
                (_screen.value as? UiScreen.ApkInstall)?.let {
                    if (it.name == entry.name) _screen.value = it.copy(phase = ApkPhase.READY, progress = 100)
                }
                _install.tryEmit(dest)
            } catch (e: Exception) {
                (_screen.value as? UiScreen.ApkInstall)?.let {
                    if (it.name == entry.name) _screen.value = it.copy(phase = ApkPhase.FAILED, error = e.message ?: "Download failed")
                }
            }
        }
    }

    /**
     * Record where an external video player stopped so the next play resumes there. Called by the
     * Activity when the player returns a result (VLC/MX report extra_position / position in ms).
     * A non-positive value means "start over".
     */
    fun recordPlaybackPosition(url: String, positionMs: Int) {
        if (positionMs > 0) playback.save(url, positionMs) else playback.clear(url)
    }

    // ── Audio player controls ─────────────────────────────────────────────
    fun audioNext() {
        (_screen.value as? UiScreen.AudioPlayer)?.let {
            if (it.hasNext) _screen.value = it.copy(index = it.index + 1)
        }
    }

    fun audioPrev() {
        (_screen.value as? UiScreen.AudioPlayer)?.let {
            if (it.hasPrev) _screen.value = it.copy(index = it.index - 1)
        }
    }

    /** Track finished on its own — auto-advance if there's a next song. */
    fun audioCompleted() = audioNext()

    // ── Real-time cast: long-poll the phone; when it casts, open in the native player ──
    private fun startPollLoop() {
        startLinksPoll()
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
            is UiScreen.Viewer -> {
                // Return to the folder listing we came from, from cache (no refetch).
                _screen.value = render(cur.server, currentPath, "", SortMode.DEFAULT)
                return true
            }
            is UiScreen.AudioPlayer -> {
                val mc = musicContext
                _screen.value = if (mc != null) UiScreen.AlbumSongs(cur.server, mc)
                                else render(cur.server, currentPath, "", SortMode.DEFAULT)
                return true
            }
            is UiScreen.AlbumSongs -> {
                _screen.value = UiScreen.MusicLibrary(cur.server, musicAlbums, loading = false)
                return true
            }
            is UiScreen.MusicLibrary -> {
                musicContext = null
                _screen.value = render(cur.server, currentPath, "", SortMode.DEFAULT)
                return true
            }
            is UiScreen.Links -> {
                _screen.value = render(cur.server, currentPath, "", SortMode.DEFAULT)
                return true
            }
            is UiScreen.LinkGroup -> {
                _screen.value = UiScreen.Links(cur.server, streamLinks, loading = false)
                return true
            }
            is UiScreen.ApkInstall -> {
                _screen.value = render(cur.server, currentPath, "", SortMode.DEFAULT)
                return true
            }
            is UiScreen.Browse -> {
                if (cur.path.isEmpty()) {
                    pollJob?.cancel()
                    linksJob?.cancel()
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
        linksJob?.cancel()
        super.onCleared()
    }
}
