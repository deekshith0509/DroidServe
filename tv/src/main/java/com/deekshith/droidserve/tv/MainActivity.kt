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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

private val BG = Color(0xFF0F172A)
private val SURFACE = Color(0xFF1E293B)
private val ACCENT = Color(0xFF38BDF8)
private val TEXT = Color(0xFFE2E8F0)
private val MUTED = Color(0xFF7C8AA0)

class MainActivity : ComponentActivity() {

    private val vm: BrowseViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val screen by vm.screen.collectAsStateWithLifecycle()
            Box(Modifier.fillMaxSize().background(BG)) {
                when (val s = screen) {
                    is UiScreen.Discovery -> DiscoveryScreen(s, onConnect = vm::connect)
                    is UiScreen.Browse -> BrowseScreen(s, onClick = vm::onEntryClick)
                    is UiScreen.Play -> PlayScreen(s)
                }
            }
        }
    }

    override fun onBackPressed() {
        if (!vm.onBack()) super.onBackPressed()
    }
}

@Composable
private fun DiscoveryScreen(state: UiScreen.Discovery, onConnect: (DiscoveredServer) -> Unit) {
    Column(Modifier.fillMaxSize().padding(48.dp)) {
        Text("📡 DroidServe TV", color = ACCENT, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Select a phone running DroidServe on your network",
            color = MUTED, fontSize = 16.sp
        )
        Spacer(Modifier.height(24.dp))
        if (state.servers.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = ACCENT, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text("Searching for servers…", color = TEXT, fontSize = 16.sp)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.servers, key = { it.name }) { srv ->
                    FocusableRow(onClick = { onConnect(srv) }) {
                        Text("🖥️", fontSize = 24.sp)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(srv.name, color = TEXT, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                            Text("${srv.host}:${srv.port}", color = MUTED, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseScreen(state: UiScreen.Browse, onClick: (RemoteEntry) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().padding(48.dp)) {
        val where = if (state.path.isEmpty()) "🏠 Home" else state.path
        Text(state.server.name, color = ACCENT, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(where, color = MUTED, fontSize = 14.sp)
        Spacer(Modifier.height(20.dp))
        when {
            state.loading -> CircularProgressIndicator(color = ACCENT)
            state.error != null -> Text("Error: ${state.error}", color = Color(0xFFF87171), fontSize = 16.sp)
            state.entries.isEmpty() -> Text("Empty folder", color = MUTED, fontSize = 16.sp)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(state.entries, focusRequester)
                { _, e -> onClick(e) }
            }
        }
    }
    LaunchedEffect(state.path, state.entries.size) {
        if (state.entries.isNotEmpty()) runCatching { focusRequester.requestFocus() }
    }
}

// Extracted so we can attach the FocusRequester to the first item only.
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    entries: List<RemoteEntry>,
    firstFocus: FocusRequester,
    onClick: (Int, RemoteEntry) -> Unit
) {
    items(entries.size, key = { entries[it].name + it }) { i ->
        val e = entries[i]
        val mod = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier
        FocusableRow(onClick = { onClick(i, e) }, extraModifier = mod) {
            Text(iconFor(e), fontSize = 22.sp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(e.name, color = TEXT, fontSize = 17.sp)
                Text(metaFor(e), color = MUTED, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun PlayScreen(state: UiScreen.Play) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(state.entry.url))
            prepare()
            playWhenReady = true
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
            }
        },
        modifier = Modifier.fillMaxSize().background(Color.Black)
    )
}

// ── Reusable focusable card row with a D-pad focus ring ─────────────────────
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
            .background(if (focused) Color(0xFF1E3A5F) else SURFACE, RoundedCornerShape(12.dp))
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) ACCENT else Color(0xFF334155),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

private fun iconFor(e: RemoteEntry): String = when {
    e.isDir -> "📁"
    e.isVideo -> "🎬"
    e.isAudio -> "🎵"
    e.isImage -> "🖼️"
    else -> "📄"
}

private fun metaFor(e: RemoteEntry): String =
    if (e.isDir) "Folder" else formatSize(e.size)

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1_048_576 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1_073_741_824 -> "%.1f MB".format(bytes / 1_048_576.0)
    else -> "%.2f GB".format(bytes / 1_073_741_824.0)
}
