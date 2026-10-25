package com.deekshith.droidserve.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deekshith.droidserve.tv.DiscoveredServer
import com.deekshith.droidserve.tv.SavedHit
import com.deekshith.droidserve.tv.SavedPlaylist
import com.deekshith.droidserve.tv.StreamLink
import com.deekshith.droidserve.tv.UiScreen
import com.deekshith.droidserve.tv.ui.ContextAction
import com.deekshith.droidserve.tv.ui.ContextMenu
import com.deekshith.droidserve.tv.ui.DsColor
import com.deekshith.droidserve.tv.ui.DsDim
import com.deekshith.droidserve.tv.ui.GridDensity
import com.deekshith.droidserve.tv.ui.densityTileMin
import com.deekshith.droidserve.tv.ui.DsIcon
import com.deekshith.droidserve.tv.ui.DsIcons
import com.deekshith.droidserve.tv.ui.DsLoading
import com.deekshith.droidserve.tv.ui.DsScreen
import com.deekshith.droidserve.tv.ui.DsTextField
import com.deekshith.droidserve.tv.ui.DsType
import com.deekshith.droidserve.tv.ui.FocusRow
import com.deekshith.droidserve.tv.ui.InitialFocus
import com.deekshith.droidserve.tv.ui.MediaTile
import com.deekshith.droidserve.tv.ui.NavChip
import com.deekshith.droidserve.tv.ui.PrimaryButton
import com.deekshith.droidserve.tv.ui.VScrollbar
import com.deekshith.droidserve.tv.ui.iconForLink
import com.deekshith.droidserve.tv.ui.onMenuKey

@Composable
fun CrashScreen(trace: String, onContinue: () -> Unit) {
    val continueFocus = remember { FocusRequester() }
    Column(
        Modifier.fillMaxSize().background(DsColor.Ink).padding(40.dp).verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DsIcon(DsIcons.Warning, tint = DsColor.Coral, size = 26.dp)
            Spacer(Modifier.width(12.dp))
            Text("DroidServe TV crashed last time", style = DsType.Section.copy(color = DsColor.Coral))
        }
        Spacer(Modifier.height(8.dp))
        Text("Share this report with the developer, or continue to the app.", style = DsType.Meta)
        Spacer(Modifier.height(18.dp))
        PrimaryButton("Continue", Modifier.focusRequester(continueFocus), icon = DsIcons.Chevron) { onContinue() }
        Spacer(Modifier.height(20.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DsColor.Ink2).padding(16.dp),
        ) { Text(trace, style = DsType.Mono.copy(color = DsColor.Muted)) }
    }
    InitialFocus(continueFocus, enabled = true, Unit)
}

/**
 * The main screen: discovered DroidServe phones at the top, and — filling the space below — the
 * playlists and channels the user saved (which play directly, no phone needed). The remote's MENU
 * button on a saved item removes it.
 */
@Composable
fun DiscoveryScreen(
    state: UiScreen.Discovery,
    onConnect: (DiscoveredServer) -> Unit,
    onManual: (String, Int) -> Unit,
    onRescan: () -> Unit,
    onFilter: (String) -> Unit,
    onOpenPlaylist: (SavedPlaylist) -> Unit,
    onPlayChannel: (StreamLink) -> Unit,
    onRemovePlaylist: (String) -> Unit,
    onRemoveChannel: (String) -> Unit,
    onSaveChannel: (StreamLink) -> Unit,
    isChannelSaved: (String) -> Boolean,
    onToggleShowAll: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    var searchOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var focusedPlaylist by remember { mutableStateOf<String?>(null) }
    var focusedChannel by remember { mutableStateOf<SavedHit?>(null) }

    val playlists = state.shownPlaylists
    val channels = state.shownChannels
    val hasSaved = state.savedPlaylists.isNotEmpty() || state.savedChannels.isNotEmpty()
    val serversPresent = state.servers.isNotEmpty() && !state.connecting
    val focusFirstPlaylist = !serversPresent && playlists.isNotEmpty()
    val focusFirstChannel = !serversPresent && playlists.isEmpty() && channels.isNotEmpty()

    Box(
        Modifier.fillMaxSize().background(DsColor.Ink).onMenuKey(enabled = !menuOpen && hasSaved) {
            if (focusedPlaylist != null || focusedChannel != null) menuOpen = true
        },
    ) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(densityTileMin(GridDensity.intValue)),
            horizontalArrangement = Arrangement.spacedBy(DsDim.Gutter),
            verticalArrangement = Arrangement.spacedBy(DsDim.Gutter),
            contentPadding = PaddingValues(horizontal = DsDim.ScreenH, vertical = DsDim.ScreenV),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("DroidServe", style = DsType.Hero, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (hasSaved) {
                            NavChip("Search", DsIcons.Search, active = searchOpen) { searchOpen = !searchOpen }
                            Spacer(Modifier.width(8.dp))
                            NavChip(if (state.showAll) "Grouped" else "All", DsIcons.Grid, active = state.showAll) { onToggleShowAll() }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Pick a phone running DroidServe, or open something you saved", style = DsType.Body.copy(color = DsColor.Muted))
                    state.error?.let { Spacer(Modifier.height(10.dp)); Text(it, style = DsType.Body.copy(color = DsColor.Coral)) }
                    if (searchOpen) {
                        Spacer(Modifier.height(12.dp))
                        DsTextField(state.filter, onFilter, "Search saved links & playlists…", Modifier.width(380.dp).focusRequester(searchFocus))
                        LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
                    }
                    Spacer(Modifier.height(20.dp))
                    SectionLabel("Devices on your network")
                    Spacer(Modifier.height(10.dp))
                }
            }

            if (state.connecting) {
                item(span = { GridItemSpan(maxLineSpan) }) { DsLoading("Connecting…") }
            } else if (state.servers.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    if (state.scanning) DsLoading("Searching the network…")
                    else Text("No phones found. Start DroidServe on your phone, then rescan.", style = DsType.Meta)
                }
            } else {
                items(state.servers.size, span = { GridItemSpan(maxLineSpan) }, key = { "srv:" + state.servers[it].host + state.servers[it].port }) { i ->
                    val srv = state.servers[i]
                    val mod = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier
                    FocusRow(onClick = { onConnect(srv) }, modifier = mod) { focused ->
                        DsIcon(DsIcons.Device, tint = if (focused) DsColor.Amber else DsColor.Muted, size = 26.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(srv.displayName.ifEmpty { srv.name }, style = DsType.Item, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${srv.host}:${srv.port}", style = DsType.Caption)
                        }
                        DsIcon(DsIcons.Chevron, tint = DsColor.Muted, size = 20.dp)
                    }
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    NavChip(if (state.scanning) "Scanning…" else "Rescan", DsIcons.Refresh, active = false) { if (!state.scanning) onRescan() }
                    ManualConnect(onManual)
                }
            }

            if (state.showAll) {
                val all = state.allLinks
                if (all.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(22.dp)); SectionLabel("All links") }
                    items(all.size, key = { "all:" + all[it].link.id }) { i ->
                        val hit = all[i]
                        val c = hit.link
                        val mod = Modifier
                            .onFocusChanged { if (it.hasFocus) { focusedChannel = hit; focusedPlaylist = null } }
                            .then(if (i == 0 && !serversPresent) Modifier.focusRequester(firstFocus) else Modifier)
                        MediaTile(c.label, hit.from?.let { "In $it" } ?: "Link", c.logo, iconForLink(c.url), 16f / 10f, mod, ContentScale.Fit) { onPlayChannel(c) }
                    }
                }
            } else {
                if (playlists.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(22.dp)); SectionLabel("Playlists") }
                    items(playlists.size, key = { "pl:" + playlists[it].name }) { i ->
                        val p = playlists[i]
                        val mod = Modifier
                            .onFocusChanged { if (it.hasFocus) { focusedPlaylist = p.name; focusedChannel = null } }
                            .then(if (i == 0 && focusFirstPlaylist) Modifier.focusRequester(firstFocus) else Modifier)
                        MediaTile(p.name, "${p.channels.size} links", null, DsIcons.ListView, 16f / 10f, mod) { onOpenPlaylist(p) }
                    }
                }
                if (channels.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(22.dp)); SectionLabel("Links") }
                    items(channels.size, key = { "ch:" + channels[it].link.id }) { i ->
                        val hit = channels[i]
                        val c = hit.link
                        val mod = Modifier
                            .onFocusChanged { if (it.hasFocus) { focusedChannel = hit; focusedPlaylist = null } }
                            .then(if (i == 0 && focusFirstChannel) Modifier.focusRequester(firstFocus) else Modifier)
                        MediaTile(c.label, hit.from?.let { "In $it" } ?: "Link", c.logo, iconForLink(c.url), 16f / 10f, mod, ContentScale.Fit) { onPlayChannel(c) }
                    }
                }
            }
        }
        VScrollbar(gridState.layoutInfo.totalItemsCount, gridState.layoutInfo.visibleItemsInfo.size, gridState.firstVisibleItemIndex)

        if (menuOpen) {
            val title = focusedPlaylist ?: focusedChannel?.link?.label ?: ""
            val actions = buildList {
                focusedPlaylist?.let { n -> add(ContextAction("Remove from saved", DsIcons.Warning, destructive = true) { onRemovePlaylist(n) }) }
                focusedChannel?.let { hit ->
                    val l = hit.link
                    if (isChannelSaved(l.id)) add(ContextAction("Remove link from Home", DsIcons.Warning, destructive = true) { onRemoveChannel(l.id) })
                    else add(ContextAction("Save link to Home", DsIcons.Link) { onSaveChannel(l) })
                    hit.from?.let { fromName -> state.savedPlaylists.firstOrNull { it.name == fromName }?.let { p -> add(ContextAction("Open $fromName", DsIcons.ListView) { onOpenPlaylist(p) }) } }
                }
            }
            if (actions.isNotEmpty()) ContextMenu(title, actions) { menuOpen = false } else menuOpen = false
        }
    }
    InitialFocus(
        firstFocus,
        enabled = !searchOpen && !state.connecting && (serversPresent || playlists.isNotEmpty() || channels.isNotEmpty()),
        state.servers.size, state.connecting, searchOpen, playlists.size, channels.size,
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = DsType.Section)
}

@Composable
private fun ManualConnect(onManual: (String, Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8080") }
    val hostFocus = remember { FocusRequester() }
    Column {
        NavChip("Enter IP manually", DsIcons.Keyboard, active = open) { open = !open }
        if (open) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DsTextField(host, { host = it }, "192.168.x.x", Modifier.width(220.dp).focusRequester(hostFocus))
                Spacer(Modifier.width(10.dp)); Text(":", style = DsType.Body.copy(color = DsColor.Muted)); Spacer(Modifier.width(10.dp))
                DsTextField(port, { v -> port = v.filter { it.isDigit() } }, "8080", Modifier.width(96.dp))
                Spacer(Modifier.width(14.dp))
                PrimaryButton("Connect") { if (host.isNotBlank()) onManual(host.trim(), port.toIntOrNull() ?: 8080) }
            }
            InitialFocus(hostFocus, enabled = true, Unit)
        }
    }
}

@Composable
fun AuthScreen(
    state: UiScreen.Auth,
    onSubmit: (DiscoveredServer, String, String) -> Unit,
    onForget: (DiscoveredServer) -> Unit,
) {
    var user by remember(state) { mutableStateOf(state.savedUsername ?: "") }
    var pass by remember(state) { mutableStateOf("") }
    val userFocus = remember { FocusRequester() }
    DsScreen {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DsIcon(DsIcons.Lock, tint = DsColor.Amber, size = 28.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(state.server.displayName, style = DsType.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("This server is password protected", style = DsType.Meta)
            }
        }
        state.error?.let { Spacer(Modifier.height(12.dp)); Text(it, style = DsType.Body.copy(color = DsColor.Coral)) }
        Spacer(Modifier.height(24.dp))
        Text("Username (optional)", style = DsType.Caption)
        Spacer(Modifier.height(6.dp))
        DsTextField(user, { user = it }, "any", Modifier.width(340.dp).focusRequester(userFocus))
        Spacer(Modifier.height(16.dp))
        Text("Password", style = DsType.Caption)
        Spacer(Modifier.height(6.dp))
        DsTextField(pass, { pass = it }, "password", Modifier.width(340.dp), password = true)
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton("Connect", icon = DsIcons.Lock) { onSubmit(state.server, user, pass) }
            if (state.savedUsername != null) NavChip("Forget saved password", null, active = false) { onForget(state.server) }
        }
        Spacer(Modifier.height(16.dp))
        Text("Your password is saved for this server and reused next time.", style = DsType.Caption)
    }
    InitialFocus(userFocus, enabled = true, state)
}
