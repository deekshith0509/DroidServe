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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deekshith.droidserve.tv.SavedPlaylist
import com.deekshith.droidserve.tv.StreamLink
import com.deekshith.droidserve.tv.UiScreen
import com.deekshith.droidserve.tv.ui.ContextAction
import com.deekshith.droidserve.tv.ui.ContextMenu
import com.deekshith.droidserve.tv.ui.DsColor
import com.deekshith.droidserve.tv.ui.DsDim
import com.deekshith.droidserve.tv.ui.DsTextField
import com.deekshith.droidserve.tv.ui.DsType
import com.deekshith.droidserve.tv.ui.InitialFocus
import com.deekshith.droidserve.tv.ui.MediaTile
import com.deekshith.droidserve.tv.ui.NavChip
import com.deekshith.droidserve.tv.ui.PrimaryButton
import com.deekshith.droidserve.tv.ui.StateMessage
import com.deekshith.droidserve.tv.ui.DsIcons
import com.deekshith.droidserve.tv.ui.iconForLink
import com.deekshith.droidserve.tv.ui.onMenuKey

@Composable
fun HomeScreen(
    state: UiScreen.Home,
    onConnectPc: () -> Unit,
    onFilter: (String) -> Unit,
    onOpenPlaylist: (SavedPlaylist) -> Unit,
    onPlayChannel: (StreamLink) -> Unit,
    onRemovePlaylist: (String) -> Unit,
    onRemoveChannel: (String) -> Unit,
) {
    var searchOpen by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }
    var menuOpen by remember { mutableStateOf(false) }
    var focusedPlaylist by remember { mutableStateOf<String?>(null) }
    var focusedChannel by remember { mutableStateOf<StreamLink?>(null) }

    val playlists = state.shownPlaylists
    val channels = state.shownChannels
    val isEmpty = state.playlists.isEmpty() && state.channels.isEmpty()

    Box(
        Modifier.fillMaxSize().onMenuKey(enabled = !menuOpen && !isEmpty) {
            if (focusedPlaylist != null || focusedChannel != null) menuOpen = true
        },
    ) {
        Column(
            Modifier.fillMaxSize().background(DsColor.Ink).padding(horizontal = DsDim.ScreenH, vertical = DsDim.ScreenV),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("DroidServe", style = DsType.Hero, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                NavChip("Search", DsIcons.Search, active = searchOpen) { searchOpen = !searchOpen }
                Spacer(Modifier.width(8.dp))
                NavChip(
                    "Connect PC", DsIcons.Device, active = false,
                    modifier = if (isEmpty) Modifier.focusRequester(firstFocus) else Modifier,
                ) { onConnectPc() }
            }
            if (searchOpen) {
                Spacer(Modifier.height(12.dp))
                DsTextField(state.filter, onFilter, "Search channels & playlists…", Modifier.width(380.dp).focusRequester(searchFocus))
                LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
            }
            Spacer(Modifier.height(18.dp))

            if (isEmpty) {
                StateMessage(
                    DsIcons.Link, "Nothing saved yet",
                    "Connect a PC, open Links, and press the menu (☰) button on your remote to save a channel or playlist here.",
                    action = { PrimaryButton("Connect PC", icon = DsIcons.Device) { onConnectPc() } },
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(DsDim.TileMin),
                    horizontalArrangement = Arrangement.spacedBy(DsDim.Gutter),
                    verticalArrangement = Arrangement.spacedBy(DsDim.Gutter),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 28.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (playlists.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Playlists") }
                        items(playlists.size, key = { "pl:" + playlists[it].name }) { i ->
                            val p = playlists[i]
                            val mod = Modifier
                                .onFocusChanged { if (it.hasFocus) { focusedPlaylist = p.name; focusedChannel = null } }
                                .then(if (i == 0) Modifier.focusRequester(firstFocus) else Modifier)
                            MediaTile(p.name, "${p.channels.size} channels", null, DsIcons.ListView, 16f / 10f, mod) { onOpenPlaylist(p) }
                        }
                    }
                    if (channels.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Channels") }
                        items(channels.size, key = { "ch:" + channels[it].id }) { i ->
                            val c = channels[i]
                            val firstChannel = playlists.isEmpty() && i == 0
                            val mod = Modifier
                                .onFocusChanged { if (it.hasFocus) { focusedChannel = c; focusedPlaylist = null } }
                                .then(if (firstChannel) Modifier.focusRequester(firstFocus) else Modifier)
                            MediaTile(c.label, "Channel", null, iconForLink(c.url), 16f / 10f, mod) { onPlayChannel(c) }
                        }
                    }
                }
            }
        }
        if (menuOpen) {
            val title = focusedPlaylist ?: focusedChannel?.label ?: ""
            val actions = buildList {
                focusedPlaylist?.let { n -> add(ContextAction("Remove from Home", DsIcons.Warning, destructive = true) { onRemovePlaylist(n) }) }
                focusedChannel?.let { c -> add(ContextAction("Remove from Home", DsIcons.Warning, destructive = true) { onRemoveChannel(c.id) }) }
            }
            if (actions.isNotEmpty()) ContextMenu(title, actions) { menuOpen = false } else menuOpen = false
        }
    }
    InitialFocus(firstFocus, enabled = !searchOpen, state.playlists.size, state.channels.size, searchOpen)
}

@Composable
fun SavedPlaylistScreen(
    state: UiScreen.PlaylistDetail,
    onFilter: (String) -> Unit,
    onPlay: (StreamLink) -> Unit,
    onRemovePlaylist: (String) -> Unit,
) {
    var searchOpen by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }
    var menuOpen by remember { mutableStateOf(false) }
    val shown = state.shown

    Box(Modifier.fillMaxSize().onMenuKey(enabled = !menuOpen) { menuOpen = true }) {
        Column(
            Modifier.fillMaxSize().background(DsColor.Ink).padding(horizontal = DsDim.ScreenH, vertical = DsDim.ScreenV),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                com.deekshith.droidserve.tv.ui.DsIcon(DsIcons.ListView, tint = DsColor.Amber, size = 24.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.name, style = DsType.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${state.links.size} channels", style = DsType.Meta)
                }
                NavChip("Search", DsIcons.Search, active = searchOpen) { searchOpen = !searchOpen }
            }
            if (searchOpen) {
                Spacer(Modifier.height(12.dp))
                DsTextField(state.filter, onFilter, "Search channels…", Modifier.width(380.dp).focusRequester(searchFocus))
                LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
            }
            Spacer(Modifier.height(16.dp))
            if (shown.isEmpty()) {
                StateMessage(DsIcons.Link, "No channels match", "Try a different search term.")
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(shown.size, key = { shown[it].id }) { i ->
                        val l = shown[i]
                        val mod = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier
                        LinkRow(l, mod) { onPlay(l) }
                    }
                }
            }
        }
        if (menuOpen) {
            ContextMenu(
                state.name,
                listOf(ContextAction("Remove from Home", DsIcons.Warning, destructive = true) { onRemovePlaylist(state.name) }),
            ) { menuOpen = false }
        }
    }
    InitialFocus(firstFocus, enabled = !searchOpen && shown.isNotEmpty(), state.name, shown.size, searchOpen)
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = DsType.Section, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
}
