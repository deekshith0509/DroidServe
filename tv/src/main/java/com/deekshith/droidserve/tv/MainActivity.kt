package com.deekshith.droidserve.tv

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BG = Color(0xFF0F172A)
private val SURFACE = Color(0xFF1E293B)
private val ACCENT = Color(0xFF38BDF8)
private val TEXT = Color(0xFFE2E8F0)
private val MUTED = Color(0xFF7C8AA0)
private val BORDER = Color(0xFF334155)
private val FOCUS_BG = Color(0xFF1E3A5F)
private val ERR = Color(0xFFF87171)

class MainActivity : ComponentActivity() {

    private val vm: BrowseViewModel by viewModels()

    private companion object { const val REQ_PLAYER = 1001 }

    // The URL of the video we last handed to an external player, so we can attribute the returned
    // resume position to the right file.
    private var lastVideoUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prune the resume-position cache on startup if it grew past 100 KB (bounded cleanup).
        PlaybackStore.pruneIfLarge(application)

        // If we crashed last run, surface the saved stack trace on-screen (we can't always
        // attach adb to a TV). Reading it clears it.
        val crashFile = App.crashFile(application)
        val lastCrash = if (crashFile.exists()) {
            val t = runCatching { crashFile.readText() }.getOrNull()
            runCatching { crashFile.delete() }
            t
        } else null

        // When a file is clicked OR the phone casts, hand the stream to the OS default player.
        lifecycleScope.launch {
            vm.open.collect { req -> openInDefaultApp(req) }
        }
        lifecycleScope.launch {
            vm.install.collect { file -> installApk(file) }
        }

        setContent {
            var crash by remember { mutableStateOf(lastCrash) }
            val c = crash
            if (c != null) {
                CrashScreen(c) { crash = null }
            } else {
                val screen by vm.screen.collectAsStateWithLifecycle()
                Box(Modifier.fillMaxSize().background(BG)) {
                    when (val s = screen) {
                        is UiScreen.Discovery -> DiscoveryScreen(s, vm::connect, vm::connectManual, vm::rescan)
                        is UiScreen.Auth -> AuthScreen(s, vm::submitAuth, vm::forgetSaved)
                        is UiScreen.Browse -> BrowseScreen(s, vm::onEntryClick, vm::setFilter, vm::setSort, vm::clearCastToast, vm::toggleView, vm::openMusic, vm::openLinks) { vm.openFolder(s.server, s.path) }
                        is UiScreen.Viewer -> ViewerScreen(s)
                        is UiScreen.AudioPlayer -> AudioPlayerScreen(s, vm::audioPrev, vm::audioNext, vm::audioCompleted)
                        is UiScreen.ApkInstall -> ApkInstallScreen(s, vm::installReady)
                        is UiScreen.MusicLibrary -> MusicLibraryScreen(s, vm::openAlbum, vm::setMusicFilter)
                        is UiScreen.AlbumSongs -> AlbumSongsScreen(s, vm::playSong)
                        is UiScreen.Links -> LinksScreen(s, vm::onLinkClick)
                    }
                }
            }
        }
    }

    /**
     * Delegate playback/viewing to whatever app the user already has (VLC, MX Player, a photo
     * viewer, etc). This gives us hardware decoding and broad codec support for free, and keeps
     * the TV app tiny. We never bundle a player.
     *
     * For video we launch for a result and pass the last resume position; players that support it
     * (VLC, MX) start there and report the stop position back, which we persist for next time.
     */
    private fun openInDefaultApp(req: OpenRequest) {
        val isVideo = req.mime.startsWith("video/")
        val uri = Uri.parse(req.url)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            // A blank MIME (arbitrary pasted links: magnet / web page / third-party endpoint) is
            // opened untyped so the OS resolves it by scheme and offers the right app. Known media
            // keeps its type so players register as handlers.
            if (req.mime.isBlank()) setData(uri) else setDataAndType(uri, req.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Pass a sidecar subtitle track to players that understand it. VLC and MX Player read
            // these extras; players that don't simply ignore them, so it's always safe to include.
            req.subUrl?.let { s ->
                val subUri = Uri.parse(s)
                putExtra("subtitles_location", s)                 // VLC for Android
                putExtra("subs", arrayOf(subUri))                 // MX Player
                putExtra("subs.name", arrayOf("Subtitles"))
            }
            // Resume position (ms). VLC uses "position" (long ms) / "from_start"; MX uses
            // "position" (int ms). Players that don't understand it just start from 0.
            if (req.resumeMs > 0) {
                putExtra("position", req.resumeMs.toLong())       // VLC
                putExtra("return_result", true)                  // MX: ask it to report back
            } else if (isVideo && !req.forceChooser) {
                putExtra("return_result", true)
            }
        }
        // Pasted links: always offer the chooser so the user picks their player (VLC / MX / Kodi /
        // browser), and don't wait for a result — these aren't tracked for resume.
        if (req.forceChooser) {
            try {
                startActivity(Intent.createChooser(intent, "Open with").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            } catch (e: Exception) {
                Toast.makeText(this, "No app can open this link", Toast.LENGTH_LONG).show()
            }
            return
        }
        lastVideoUrl = if (isVideo) req.url else null
        try {
            if (isVideo) startActivityForResult(intent, REQ_PLAYER) else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(intent)
            }
        } catch (e: Exception) {
            // No app registered for this type — offer a chooser across everything that can VIEW.
            try {
                startActivity(Intent.createChooser(intent, "Open with").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e2: Exception) {
                Toast.makeText(this, "No app can open this file", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Hand a downloaded APK to the system installer via a FileProvider content:// URI. On API 26+
    // the app needs the per-source "install unknown apps" grant; if missing, route the user there.
    private fun installApk(file: java.io.File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Toast.makeText(this, "Allow DroidServe to install apps, then press Install", Toast.LENGTH_LONG).show()
                return
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Toast.makeText(this, "Install failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Capture the position an external player reports on exit so we can resume next time. VLC and
    // MX both return an "extra_position"/"position" (ms) in the result Intent.
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PLAYER) return
        val url = lastVideoUrl ?: return
        val pos = data?.let {
            when {
                it.hasExtra("extra_position") -> it.getLongExtra("extra_position", 0).toInt()  // MX
                it.hasExtra("position") -> it.getLongExtra("position", 0).toInt()               // VLC
                else -> -1
            }
        } ?: -1
        // Only record when the player actually reported a position (>=0). A player that ignores
        // the result contract leaves the previous mark untouched.
        if (pos >= 0) vm.recordPlaybackPosition(url, pos)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!vm.onBack()) super.onBackPressed()
    }
}

// ── Crash reporter ────────────────────────────────────────────────────────
@Composable
private fun CrashScreen(trace: String, onContinue: () -> Unit) {
    val continueFocus = remember { FocusRequester() }
    Column(
        Modifier.fillMaxSize().background(BG).padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("⚠️ DroidServe TV crashed last time", color = ERR, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Share this with the developer, or continue to the app:", color = MUTED, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        FocusableChip("Continue →", Modifier.focusRequester(continueFocus)) { onContinue() }
        Spacer(Modifier.height(16.dp))
        Text(trace, color = TEXT, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
    LaunchedEffect(Unit) {
        repeat(10) { if (runCatching { continueFocus.requestFocus() }.isSuccess) return@LaunchedEffect; kotlinx.coroutines.delay(60) }
    }
}

// ── Discovery ───────────────────────────────────────────────────────────────
@Composable
private fun DiscoveryScreen(
    state: UiScreen.Discovery,
    onConnect: (DiscoveredServer) -> Unit,
    onManual: (String, Int) -> Unit,
    onRescan: () -> Unit
) {
    val firstServerFocus = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().padding(48.dp)) {
        Text("📡 DroidServe TV", color = ACCENT, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Pick a phone running DroidServe on your Wi-Fi", color = MUTED, fontSize = 16.sp)
        state.error?.let { Spacer(Modifier.height(10.dp)); Text(it, color = ERR, fontSize = 14.sp) }
        Spacer(Modifier.height(20.dp))

        if (state.connecting) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = ACCENT, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp)); Text("Connecting…", color = TEXT, fontSize = 16.sp)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.servers.isEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (state.scanning) {
                                CircularProgressIndicator(color = ACCENT, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text("Searching the network…", color = TEXT, fontSize = 15.sp)
                            } else {
                                Text("No servers found. Start DroidServe on your phone, then rescan.",
                                    color = MUTED, fontSize = 15.sp)
                            }
                        }
                    }
                }
                items(state.servers.size, key = { "${state.servers[it].host}:${state.servers[it].port}" }) { i ->
                    val srv = state.servers[i]
                    val mod = if (i == 0) Modifier.focusRequester(firstServerFocus) else Modifier
                    FocusableRow(onClick = { onConnect(srv) }, extraModifier = mod) {
                        Text("🖥️", fontSize = 24.sp)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(srv.name, color = TEXT, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                            Text("${srv.host}:${srv.port}", color = MUTED, fontSize = 13.sp)
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SortChip(if (state.scanning) "Scanning…" else "🔄 Rescan", false) {
                            if (!state.scanning) onRescan()
                        }
                        Spacer(Modifier.width(10.dp))
                        ManualConnect(onManual)
                    }
                }
            }
        }
    }
    LaunchedEffect(state.servers.isNotEmpty()) {
        if (state.servers.isNotEmpty()) {
            repeat(10) {
                if (runCatching { firstServerFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                kotlinx.coroutines.delay(60)
            }
        }
    }
}

@Composable
private fun ManualConnect(onManual: (String, Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8080") }
    val hostFocus = remember { FocusRequester() }
    Column {
        // Opt-in so the text fields never grab the initial D-pad focus off the server list.
        SortChip("⌨ Enter IP manually", open) { open = !open }
        if (open) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                InputField(host, { host = it }, "192.168.x.x", Modifier.width(220.dp).focusRequester(hostFocus))
                Spacer(Modifier.width(10.dp)); Text(":", color = MUTED); Spacer(Modifier.width(10.dp))
                InputField(port, { port = it.filter { c -> c.isDigit() } }, "8080", Modifier.width(90.dp))
                Spacer(Modifier.width(14.dp))
                FocusableChip("Connect") {
                    if (host.isNotBlank()) onManual(host.trim(), port.toIntOrNull() ?: 8080)
                }
            }
            LaunchedEffect(Unit) { runCatching { hostFocus.requestFocus() } }
        }
    }
}

// ── Auth ──────────────────────────────────────────────────────────────────
@Composable
private fun AuthScreen(
    state: UiScreen.Auth,
    onSubmit: (DiscoveredServer, String, String) -> Unit,
    onForget: (DiscoveredServer) -> Unit
) {
    // Pre-fill the username we remembered for this server, if any.
    var user by remember(state) { mutableStateOf(state.savedUsername ?: "") }
    var pass by remember(state) { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(48.dp)) {
        Text("🔒 ${state.server.name}", color = ACCENT, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("This server is password protected", color = MUTED, fontSize = 15.sp)
        state.error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = ERR, fontSize = 14.sp) }
        Spacer(Modifier.height(20.dp))
        Text("Username (optional)", color = MUTED, fontSize = 13.sp)
        InputField(user, { user = it }, "any", Modifier.width(320.dp))
        Spacer(Modifier.height(14.dp))
        Text("Password", color = MUTED, fontSize = 13.sp)
        InputField(pass, { pass = it }, "password", Modifier.width(320.dp), password = true)
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FocusableChip("Connect") { onSubmit(state.server, user, pass) }
            // Only meaningful once something has been remembered for this server.
            if (state.savedUsername != null) {
                Spacer(Modifier.width(12.dp))
                SortChip("Forget saved password", false) { onForget(state.server) }
            }
        }
        Text("Your password is saved for this server and reused next time.",
            color = MUTED, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp))
    }
}

// ── In-app viewer (text + image) ────────────────────────────────────────────
@Composable
private fun ViewerScreen(state: UiScreen.Viewer) {
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
        Text(state.name, color = ACCENT, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2)
        Text("Back to close", color = MUTED, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        when {
            state.loading -> CircularProgressIndicator(color = ACCENT)
            state.error != null -> Text("Error: ${state.error}", color = ERR, fontSize = 15.sp)
            state.kind == ViewerKind.TEXT -> {
                Text(
                    state.text ?: "",
                    color = TEXT, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                )
            }
            state.kind == ViewerKind.IMAGE && state.imageUrl != null -> {
                RemoteImage(state.imageUrl)
            }
        }
    }
}

/** Decode a remote image off the main thread (no image library, works on API 21+). */
@Composable
private fun RemoteImage(url: String) {
    var bmp by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                fun openStream() = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 5000; readTimeout = 10000
                }
                // Pass 1: read dimensions only (cheap — just the header), so a 48MP original can't
                // allocate a ~200MB bitmap and OOM-crash a low-RAM TV box.
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                openStream().let { c -> try { c.inputStream.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) } } finally { c.disconnect() } }
                // Pass 2: decode downsampled to roughly the display size.
                val opts = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 2560)
                    inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                }
                openStream().let { c -> try { c.inputStream.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) } } finally { c.disconnect() } }
            }.getOrNull()
        }
        if (result != null) bmp = result else failed = true
    }
    when {
        bmp != null -> Image(
            bitmap = bmp!!.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
        failed -> Text("Could not load image", color = ERR, fontSize = 15.sp)
        else -> CircularProgressIndicator(color = ACCENT)
    }
}

// ── In-app audio player ──────────────────────────────────────────────────────
@Composable
private fun AudioPlayerScreen(
    state: UiScreen.AudioPlayer,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onCompleted: () -> Unit
) {
    val track = state.current
    var playing by remember { mutableStateOf(true) }
    var positionMs by remember(track.url) { mutableStateOf(0) }
    var durationMs by remember(track.url) { mutableStateOf(0) }
    var failed by remember(track.url) { mutableStateOf(false) }

    // One MediaPlayer, recreated whenever the track URL changes. Released on dispose.
    val player = remember { android.media.MediaPlayer() }
    DisposableEffect(track.url) {
        failed = false; positionMs = 0; durationMs = 0
        runCatching {
            player.reset()
            player.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC)
            player.setDataSource(track.url)
            player.setOnPreparedListener { mp -> durationMs = mp.duration; mp.start(); playing = true }
            player.setOnCompletionListener { onCompleted() }
            player.setOnErrorListener { _, _, _ -> failed = true; true }
            player.prepareAsync()
        }.onFailure { failed = true }
        onDispose { runCatching { player.reset() } }
    }
    // Release the player entirely when leaving the screen.
    DisposableEffect(Unit) { onDispose { runCatching { player.release() } } }

    // Poll position while playing so the progress line advances.
    LaunchedEffect(track.url, playing) {
        while (playing && !failed) {
            positionMs = runCatching { player.currentPosition }.getOrDefault(positionMs)
            kotlinx.coroutines.delay(500)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("🎵", fontSize = 64.sp)
        Spacer(Modifier.height(16.dp))
        Text(track.name, color = TEXT, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2)
        Text(state.server.name, color = MUTED, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        Text("Track ${state.index + 1} of ${state.queue.size}", color = MUTED, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))
        if (failed) {
            Text("Couldn't play this file", color = ERR, fontSize = 15.sp)
        } else {
            Text("${fmtTime(positionMs)}  /  ${fmtTime(durationMs)}", color = ACCENT, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
            Spacer(Modifier.height(10.dp))
            val frac = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth(0.6f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(BORDER)) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(ACCENT))
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SortChip("⏮ Prev", false) { if (state.hasPrev) onPrev() }
            Spacer(Modifier.width(12.dp))
            FocusableChip(if (playing) "⏸ Pause" else "▶ Play") {
                if (playing) { runCatching { player.pause() }; playing = false }
                else { runCatching { player.start() }; playing = true }
            }
            Spacer(Modifier.width(12.dp))
            SortChip("Next ⏭", false) { if (state.hasNext) onNext() }
        }
        Spacer(Modifier.height(16.dp))
        Text("Back to close", color = MUTED, fontSize = 12.sp)
    }
}

private fun fmtTime(ms: Int): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60)
}

// ── Browse ──────────────────────────────────────────────────────────────────
@Composable
private fun BrowseScreen(
    state: UiScreen.Browse,
    onClick: (RemoteEntry) -> Unit,
    onFilter: (String) -> Unit,
    onSort: (SortMode) -> Unit,
    onClearCast: () -> Unit,
    onToggleView: () -> Unit,
    onMusic: () -> Unit,
    onLinks: () -> Unit,
    onRetry: () -> Unit
) {
    val firstItemFocus = remember { FocusRequester() }
    // Search is opt-in: on a 10-foot D-pad UI an always-on text field would grab initial
    // focus, trap the remote, and pop the on-screen keyboard. Instead the list is focused by
    // default and a "🔍 Search" chip reveals the filter field only when the user wants it.
    var searchOpen by remember(state.path) { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }

    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
        Text(state.server.name, color = ACCENT, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(breadcrumb(state.path), color = MUTED, fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            SortChip("🔍 Search", searchOpen) { searchOpen = !searchOpen }
            Spacer(Modifier.width(8.dp))
            SortChip("Default", state.sort == SortMode.DEFAULT) { onSort(SortMode.DEFAULT) }
            Spacer(Modifier.width(8.dp))
            SortChip("Name", state.sort == SortMode.NAME) { onSort(SortMode.NAME) }
            Spacer(Modifier.width(8.dp))
            SortChip("Size ↓", state.sort == SortMode.SIZE) { onSort(SortMode.SIZE) }
            Spacer(Modifier.width(8.dp))
            SortChip(if (state.gridMode) "☰ List" else "⊞ Grid", false) { onToggleView() }
            Spacer(Modifier.width(8.dp))
            SortChip("🎵 Music", false) { onMusic() }
            Spacer(Modifier.width(8.dp))
            SortChip("🔗 Links", false) { onLinks() }
        }
        if (searchOpen) {
            Spacer(Modifier.height(8.dp))
            InputField(state.filter, onFilter, "Filter files…",
                Modifier.width(360.dp).focusRequester(searchFocus))
            LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
        }
        Spacer(Modifier.height(6.dp))
        Text(statusLine(state), color = MUTED, fontSize = 12.sp)
        state.casting?.let {
            Spacer(Modifier.height(6.dp))
            Text("📲 Casting from phone → opening in your player…", color = ACCENT, fontSize = 13.sp)
            // Auto-dismiss the toast a few seconds after it appears so it doesn't linger.
            LaunchedEffect(it) { kotlinx.coroutines.delay(4000); onClearCast() }
        }
        Spacer(Modifier.height(10.dp))

        when {
            state.loading -> CircularProgressIndicator(color = ACCENT)
            state.error != null -> {
                val retryFocus = remember { FocusRequester() }
                Column {
                    Text("Error: ${state.error}", color = ERR, fontSize = 16.sp)
                    Spacer(Modifier.height(14.dp))
                    FocusableChip("🔄 Retry", Modifier.focusRequester(retryFocus)) { onRetry() }
                }
                LaunchedEffect(state.error) {
                    repeat(10) { if (runCatching { retryFocus.requestFocus() }.isSuccess) return@LaunchedEffect; kotlinx.coroutines.delay(60) }
                }
            }
            state.entries.isEmpty() ->
                Text(if (state.total == 0) "Empty folder" else "No items match your filter.",
                    color = MUTED, fontSize = 15.sp)
            state.gridMode -> LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.entries.size, key = { state.entries[it].name + it }) { i ->
                    val e = state.entries[i]
                    val mod = if (i == 0) Modifier.focusRequester(firstItemFocus) else Modifier
                    PosterCard(e, mod) { onClick(e) }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(12.dp)); ServerFooter(state) }
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                entryRows(state.entries, firstItemFocus, onClick)
                item { Spacer(Modifier.height(12.dp)); ServerFooter(state) }
            }
        }
    }
    // Focus the first row on load so the remote has an immediate cursor on the content
    // (never the search field). Retry briefly: on slower TVs the LazyColumn composes its
    // first item a frame or two after this effect first runs, so a single requestFocus can
    // land before the target exists.
    LaunchedEffect(state.path, state.entries.size, searchOpen) {
        if (!searchOpen && state.entries.isNotEmpty()) {
            repeat(10) {
                if (runCatching { firstItemFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                kotlinx.coroutines.delay(60)
            }
        }
    }
}

private fun LazyListScope.entryRows(
    entries: List<RemoteEntry>,
    firstFocus: FocusRequester,
    onClick: (RemoteEntry) -> Unit
) {
    items(entries.size, key = { entries[it].name + it }) { i ->
        val e = entries[i]
        val mod = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier
        FocusableRow(onClick = { onClick(e) }, extraModifier = mod) {
            if (!e.isDir && e.thumbUrl != null)
                Thumb(e.thumbUrl, iconFor(e), Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)))
            else Text(iconFor(e), fontSize = 22.sp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(e.name, color = TEXT, fontSize = 17.sp)
                Text(metaFor(e), color = MUTED, fontSize = 12.sp)
            }
            Text(if (e.isDir) "›" else "▶", color = MUTED, fontSize = 18.sp)
        }
    }
}

@Composable
private fun ApkInstallScreen(state: UiScreen.ApkInstall, onInstall: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("📱", fontSize = 60.sp)
        Spacer(Modifier.height(14.dp))
        Text(state.name, color = TEXT, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2)
        Spacer(Modifier.height(18.dp))
        when (state.phase) {
            ApkPhase.DOWNLOADING -> {
                CircularProgressIndicator(color = ACCENT)
                Spacer(Modifier.height(12.dp))
                Text("Downloading… ${state.progress}%", color = MUTED, fontSize = 15.sp)
            }
            ApkPhase.READY -> {
                Text("Opening installer — confirm on screen.", color = MUTED, fontSize = 15.sp)
                Spacer(Modifier.height(16.dp))
                FocusableChip("Install") { onInstall() }
                Spacer(Modifier.height(10.dp))
                Text("If it warns about 'unknown apps', allow DroidServe, then press Install.",
                    color = MUTED, fontSize = 12.sp)
            }
            ApkPhase.FAILED -> Text("Failed: ${state.error}", color = ERR, fontSize = 15.sp)
        }
        Spacer(Modifier.height(16.dp))
        Text("Back to cancel", color = MUTED, fontSize = 12.sp)
    }
}

// ── Music library ──────────────────────────────────────────────────────────
@Composable
private fun MusicLibraryScreen(
    state: UiScreen.MusicLibrary,
    onAlbum: (MusicAlbum) -> Unit,
    onFilter: (String) -> Unit
) {
    var searchOpen by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
        Text("🎵 Music", color = ACCENT, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("${state.albums.size} albums · ${state.albums.sumOf { it.songs.size }} songs", color = MUTED, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        SortChip("🔍 Search", searchOpen) { searchOpen = !searchOpen }
        if (searchOpen) {
            Spacer(Modifier.height(8.dp))
            InputField(state.filter, onFilter, "Search songs / albums…", Modifier.width(360.dp).focusRequester(searchFocus))
            LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
        }
        Spacer(Modifier.height(10.dp))
        when {
            state.loading -> CircularProgressIndicator(color = ACCENT)
            state.error != null -> Text("Error: ${state.error}", color = ERR, fontSize = 15.sp)
            state.albums.isEmpty() -> Text("No music found in the shared folder.", color = MUTED, fontSize = 15.sp)
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val shown = state.shown
                items(shown.size, key = { shown[it].path + it }) { i ->
                    val alb = shown[i]
                    val mod = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier
                    AlbumCard(alb, mod) { onAlbum(alb) }
                }
            }
        }
    }
    LaunchedEffect(state.albums.size, searchOpen) {
        if (!searchOpen && state.albums.isNotEmpty())
            repeat(10) { if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect; kotlinx.coroutines.delay(60) }
    }
}

@Composable
private fun AlbumCard(album: MusicAlbum, extraModifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        extraModifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .background(if (focused) FOCUS_BG else SURFACE)
            .border(if (focused) 3.dp else 1.dp, if (focused) ACCENT else BORDER, RoundedCornerShape(12.dp))
    ) {
        Thumb(album.cover, "🎵", Modifier.fillMaxWidth().aspectRatio(1f))
        Column(Modifier.padding(10.dp)) {
            Text(album.name, color = TEXT, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2)
            Text("${album.songs.size} songs", color = MUTED, fontSize = 11.sp)
        }
    }
}

@Composable
private fun AlbumSongsScreen(state: UiScreen.AlbumSongs, onPlay: (MusicAlbum, Int) -> Unit) {
    val album = state.album
    val firstFocus = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Thumb(album.cover, "🎵", Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(album.name, color = ACCENT, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                Text("${album.songs.size} songs · Back to albums", color = MUTED, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(album.songs.size, key = { album.songs[it].name + it }) { i ->
                val s = album.songs[i]
                val mod = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier
                FocusableRow(onClick = { onPlay(album, i) }, extraModifier = mod) {
                    Text("▶", color = MUTED, fontSize = 16.sp)
                    Spacer(Modifier.width(14.dp))
                    Text(s.name, color = TEXT, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 2)
                }
            }
        }
    }
    LaunchedEffect(album.path) {
        repeat(10) { if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect; kotlinx.coroutines.delay(60) }
    }
}

// ── Streaming links ──────────────────────────────────────────────────────────
@Composable
private fun LinksScreen(state: UiScreen.Links, onOpen: (StreamLink) -> Unit) {
    val firstFocus = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
        Text("🔗 Streaming Links", color = ACCENT, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("${state.links.size} links · paste more from the DroidServe web UI on your phone",
            color = MUTED, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        when {
            state.loading -> CircularProgressIndicator(color = ACCENT)
            state.error != null -> Text("Error: ${state.error}", color = ERR, fontSize = 15.sp)
            state.links.isEmpty() -> Text(
                "No links yet. Open DroidServe on your phone, tap 🔗, and paste a video or stream URL — it appears here instantly.",
                color = MUTED, fontSize = 15.sp
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.links.size, key = { state.links[it].id }) { i ->
                    val l = state.links[i]
                    val mod = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier
                    FocusableRow(onClick = { onOpen(l) }, extraModifier = mod) {
                        Text(linkIcon(l.url), fontSize = 22.sp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(l.label, color = TEXT, fontSize = 17.sp, maxLines = 1)
                            Text(l.url, color = MUTED, fontSize = 12.sp, maxLines = 1)
                        }
                        Text("▶", color = MUTED, fontSize = 18.sp)
                    }
                }
            }
        }
    }
    LaunchedEffect(state.links.size) {
        if (state.links.isNotEmpty())
            repeat(10) { if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect; kotlinx.coroutines.delay(60) }
    }
}

private fun linkIcon(u: String): String = when {
    u.startsWith("magnet:", ignoreCase = true) -> "🧲"
    Regex("\\.(mp3|flac|aac|ogg|wav|m4a|opus)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(u) -> "🎵"
    else -> "🎬"
}

// Power-of-two downsample factor so a decoded image lands near [target] px on its longest edge.
private fun sampleSize(w: Int, h: Int, target: Int): Int {
    if (w <= 0 || h <= 0) return 1
    var s = 1
    while (w / (s * 2) >= target || h / (s * 2) >= target) s *= 2
    return s
}

// Thumbnail tile: paints the cached bitmap instantly, else loads off-main and falls back to the
// type emoji. No image library — same HttpURLConnection + BitmapFactory path as the image viewer.
@Composable
private fun Thumb(url: String?, fallback: String, modifier: Modifier = Modifier) {
    var bmp by remember(url) { mutableStateOf(url?.let { ThumbLoader.cached(it) }) }
    LaunchedEffect(url) { if (url != null && bmp == null) bmp = ThumbLoader.load(url) }
    Box(modifier.background(SURFACE), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(fallback, fontSize = 30.sp)
    }
}

@Composable
private fun PosterCard(e: RemoteEntry, extraModifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        extraModifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .background(if (focused) FOCUS_BG else SURFACE)
            .border(if (focused) 3.dp else 1.dp, if (focused) ACCENT else BORDER, RoundedCornerShape(12.dp))
    ) {
        Thumb(
            if (e.isDir) null else e.thumbUrl,
            iconFor(e),
            Modifier.fillMaxWidth().aspectRatio(16f / 10f)
        )
        Column(Modifier.padding(10.dp)) {
            Text(e.name, color = TEXT, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2)
            Text(metaFor(e), color = MUTED, fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
private fun ServerFooter(state: UiScreen.Browse) {
    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, BORDER, RoundedCornerShape(11.dp))
            .background(SURFACE, RoundedCornerShape(11.dp))
            .padding(14.dp)
    ) {
        Text("ℹ️ Server info", color = ACCENT, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("${state.fileCount} files · ${state.dirCount} folders · ${formatSize(state.totalBytes)} in this folder",
            color = MUTED, fontSize = 11.sp)
        Text("Served by ${state.server.name} @ ${state.server.host}:${state.server.port}",
            color = MUTED, fontSize = 11.sp)
        Text("Text, images & audio open in-app; video opens in your device's player (VLC / MX)", color = MUTED, fontSize = 11.sp)
    }
}

// ── Reusable widgets ──────────────────────────────────────────────────────────
@Composable
private fun FocusableRow(
    onClick: () -> Unit,
    extraModifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier = extraModifier
            .fillMaxWidth()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .background(if (focused) FOCUS_BG else SURFACE, RoundedCornerShape(12.dp))
            .border(if (focused) 3.dp else 1.dp, if (focused) ACCENT else BORDER, RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
private fun SortChip(label: String, active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Text(
        label, color = if (active || focused) ACCENT else TEXT, fontSize = 13.sp,
        modifier = Modifier
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .background(SURFACE, RoundedCornerShape(7.dp))
            .border(1.dp, if (focused || active) ACCENT else BORDER, RoundedCornerShape(7.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun FocusableChip(label: String, extraModifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Text(
        label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        modifier = extraModifier
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .background(if (focused) Color(0xFF0EA5E9) else ACCENT, RoundedCornerShape(8.dp))
            .border(if (focused) 3.dp else 0.dp, Color.White, RoundedCornerShape(8.dp))
            .padding(horizontal = 20.dp, vertical = 8.dp)
    )
}

@Composable
private fun InputField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    password: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
            .background(BG, RoundedCornerShape(9.dp))
            .border(if (focused) 2.dp else 1.5.dp, if (focused) ACCENT else BORDER, RoundedCornerShape(9.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        if (value.isEmpty()) Text(placeholder, color = MUTED, fontSize = 15.sp)
        BasicTextField(
            value = value, onValueChange = onChange, interactionSource = interaction, singleLine = true,
            textStyle = TextStyle(color = TEXT, fontSize = 15.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(ACCENT),
            visualTransformation = if (password)
                androidx.compose.ui.text.input.PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────
private fun breadcrumb(path: String): String =
    if (path.isEmpty()) "🏠 Home" else "🏠 Home / " + path.replace("/", " / ")

private fun statusLine(s: UiScreen.Browse): String {
    val shown = s.entries.size
    return if (s.filter.isBlank()) "$shown items" else "$shown of ${s.total} items"
}

private fun iconFor(e: RemoteEntry): String = when {
    e.isDir -> "📁"; e.isVideo -> "🎬"; e.isAudio -> "🎵"; e.isImage -> "🖼️"; e.isApk -> "📱"; e.isText -> "📝"; else -> "📄"
}

private fun metaFor(e: RemoteEntry): String {
    if (e.isDir) return "Folder"
    val date = formatDate(e.modified)
    val base = if (date.isEmpty()) formatSize(e.size) else "${formatSize(e.size)} · $date"
    // Surface that a subtitle track will be offered to the external player.
    return if (e.subUrl != null) "$base · CC" else base
}

private val TV_DATE_FMT = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
private fun formatDate(ms: Long): String = if (ms <= 0L) "" else TV_DATE_FMT.format(java.util.Date(ms))

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1_048_576 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1_073_741_824 -> "%.1f MB".format(bytes / 1_048_576.0)
    else -> "%.2f GB".format(bytes / 1_073_741_824.0)
}
