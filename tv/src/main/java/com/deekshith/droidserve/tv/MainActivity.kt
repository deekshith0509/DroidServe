package com.deekshith.droidserve.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage

private val BG = Color(0xFF0F172A)
private val SURFACE = Color(0xFF1E293B)
private val SURFACE2 = Color(0xFF243449)
private val ACCENT = Color(0xFF38BDF8)
private val TEXT = Color(0xFFE2E8F0)
private val MUTED = Color(0xFF7C8AA0)
private val BORDER = Color(0xFF334155)
private val FOCUS_BG = Color(0xFF1E3A5F)
private val ERR = Color(0xFFF87171)

class MainActivity : ComponentActivity() {

    private val vm: BrowseViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val screen by vm.screen.collectAsStateWithLifecycle()
            Box(Modifier.fillMaxSize().background(BG)) {
                when (val s = screen) {
                    is UiScreen.Discovery -> DiscoveryScreen(s, vm::connect, vm::connectManual)
                    is UiScreen.Auth -> AuthScreen(s, vm::submitAuth)
                    is UiScreen.Browse -> BrowseScreen(s, vm::onEntryClick, vm::setFilter, vm::setSort)
                    is UiScreen.Play -> PlayScreen(s)
                    is UiScreen.ViewImage -> ImageScreen(s)
                    is UiScreen.ViewText -> TextScreen(s)
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!vm.onBack()) super.onBackPressed()
    }
}

// ── Discovery ───────────────────────────────────────────────────────────────
@Composable
private fun DiscoveryScreen(
    state: UiScreen.Discovery,
    onConnect: (DiscoveredServer) -> Unit,
    onManual: (String, Int) -> Unit
) {
    val firstServerFocus = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().padding(48.dp)) {
        Text("📡 DroidServe TV", color = ACCENT, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Pick a phone running DroidServe on your Wi-Fi", color = MUTED, fontSize = 16.sp)
        state.error?.let {
            Spacer(Modifier.height(10.dp)); Text(it, color = ERR, fontSize = 14.sp)
        }
        Spacer(Modifier.height(20.dp))

        if (state.connecting) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = ACCENT, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp)); Text("Connecting…", color = TEXT, fontSize = 16.sp)
            }
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.servers.isEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = ACCENT, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Searching the network…", color = TEXT, fontSize = 15.sp)
                    }
                }
            }
            items(state.servers.size, key = { state.servers[it].name }) { i ->
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
            item { Spacer(Modifier.height(8.dp)); ManualConnect(onManual) }
        }
    }
    // Give the remote an immediate cursor as soon as the first server appears.
    LaunchedEffect(state.servers.isNotEmpty()) {
        if (state.servers.isNotEmpty()) runCatching { firstServerFocus.requestFocus() }
    }
}

@Composable
private fun ManualConnect(onManual: (String, Int) -> Unit) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8080") }
    Column {
        Text("Or enter manually:", color = MUTED, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            InputField(host, { host = it }, "192.168.x.x", Modifier.width(220.dp))
            Spacer(Modifier.width(10.dp))
            Text(":", color = MUTED)
            Spacer(Modifier.width(10.dp))
            InputField(port, { port = it.filter { c -> c.isDigit() } }, "8080", Modifier.width(90.dp))
            Spacer(Modifier.width(14.dp))
            FocusableChip("Connect") {
                if (host.isNotBlank()) onManual(host.trim(), port.toIntOrNull() ?: 8080)
            }
        }
    }
}

// ── Auth ──────────────────────────────────────────────────────────────────
@Composable
private fun AuthScreen(state: UiScreen.Auth, onSubmit: (DiscoveredServer, String, String) -> Unit) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
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
        FocusableChip("Connect") { onSubmit(state.server, user, pass) }
    }
}

// ── Browse ──────────────────────────────────────────────────────────────────
@Composable
private fun BrowseScreen(
    state: UiScreen.Browse,
    onClick: (RemoteEntry) -> Unit,
    onFilter: (String) -> Unit,
    onSort: (SortMode) -> Unit
) {
    val firstItemFocus = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
        // Header: title + breadcrumb
        Text(state.server.name, color = ACCENT, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(breadcrumb(state.path), color = MUTED, fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))

        // Toolbar: filter box + sort chips
        Row(verticalAlignment = Alignment.CenterVertically) {
            InputField(state.filter, onFilter, "Filter files…", Modifier.width(280.dp))
            Spacer(Modifier.width(16.dp))
            SortChip("Default", state.sort == SortMode.DEFAULT) { onSort(SortMode.DEFAULT) }
            Spacer(Modifier.width(8.dp))
            SortChip("Name", state.sort == SortMode.NAME) { onSort(SortMode.NAME) }
            Spacer(Modifier.width(8.dp))
            SortChip("Size ↓", state.sort == SortMode.SIZE) { onSort(SortMode.SIZE) }
        }
        Spacer(Modifier.height(6.dp))
        Text(statusLine(state), color = MUTED, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))

        when {
            state.loading -> CircularProgressIndicator(color = ACCENT)
            state.error != null -> Text("Error: ${state.error}", color = ERR, fontSize = 16.sp)
            state.entries.isEmpty() ->
                Text(if (state.total == 0) "Empty folder" else "No items match your filter.",
                    color = MUTED, fontSize = 15.sp)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                entryRows(state.entries, firstItemFocus, onClick)
                item {
                    Spacer(Modifier.height(12.dp))
                    ServerFooter(state)
                }
            }
        }
    }
    LaunchedEffect(state.path, state.entries.size) {
        if (state.entries.isNotEmpty()) runCatching { firstItemFocus.requestFocus() }
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
            Text(iconFor(e), fontSize = 22.sp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(e.name, color = TEXT, fontSize = 17.sp)
                Text(metaFor(e), color = MUTED, fontSize = 12.sp)
            }
            if (e.isDir) Text("›", color = MUTED, fontSize = 22.sp)
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
        Text(
            "${state.fileCount} files · ${state.dirCount} folders · ${formatSize(state.totalBytes)} in this folder",
            color = MUTED, fontSize = 11.sp
        )
        Text("Served by ${state.server.name} @ ${state.server.host}:${state.server.port}",
            color = MUTED, fontSize = 11.sp)
    }
}

// ── Media / viewers ──────────────────────────────────────────────────────────
@Composable
private fun PlayScreen(state: UiScreen.Play) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(state.entry.url)); prepare(); playWhenReady = true
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { player.release() } }
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } },
        modifier = Modifier.fillMaxSize().background(Color.Black)
    )
}

@Composable
private fun ImageScreen(state: UiScreen.ViewImage) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        AsyncImage(model = state.entry.url, contentDescription = state.entry.name,
            modifier = Modifier.fillMaxSize())
        Text(state.entry.name, color = TEXT, fontSize = 13.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)
                .background(Color(0xAA000000), RoundedCornerShape(6.dp)).padding(8.dp))
    }
}

@Composable
private fun TextScreen(state: UiScreen.ViewText) {
    Column(Modifier.fillMaxSize().padding(40.dp)) {
        Text(state.entry.name, color = ACCENT, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        when {
            state.loading -> CircularProgressIndicator(color = ACCENT)
            state.error != null -> Text("Error: ${state.error}", color = ERR)
            else -> Box(
                Modifier.fillMaxSize().background(SURFACE, RoundedCornerShape(8.dp))
                    .padding(16.dp).verticalScroll(rememberScrollState())
            ) {
                Text(state.content ?: "", color = TEXT, fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace)
            }
        }
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
        label,
        color = if (active || focused) ACCENT else TEXT,
        fontSize = 13.sp,
        modifier = Modifier
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .background(SURFACE, RoundedCornerShape(7.dp))
            .border(1.dp, if (focused || active) ACCENT else BORDER, RoundedCornerShape(7.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun FocusableChip(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Text(
        label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier
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
            value = value,
            onValueChange = onChange,
            interactionSource = interaction,
            singleLine = true,
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
    return if (s.filter.isBlank()) "$shown items"
    else "$shown of ${s.total} items"
}

private fun iconFor(e: RemoteEntry): String = when {
    e.isDir -> "📁"
    e.isVideo -> "🎬"
    e.isAudio -> "🎵"
    e.isImage -> "🖼️"
    e.isText -> "📝"
    else -> "📄"
}

private fun metaFor(e: RemoteEntry): String = if (e.isDir) "Folder" else formatSize(e.size)

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1_048_576 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1_073_741_824 -> "%.1f MB".format(bytes / 1_048_576.0)
    else -> "%.2f GB".format(bytes / 1_073_741_824.0)
}
