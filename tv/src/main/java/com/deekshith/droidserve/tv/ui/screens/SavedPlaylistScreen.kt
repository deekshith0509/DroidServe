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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.deekshith.droidserve.tv.StreamLink
import com.deekshith.droidserve.tv.UiScreen
import com.deekshith.droidserve.tv.ui.ContextAction
import com.deekshith.droidserve.tv.ui.ContextMenu
import com.deekshith.droidserve.tv.ui.DsColor
import com.deekshith.droidserve.tv.ui.DsDim
import com.deekshith.droidserve.tv.ui.DsIcon
import com.deekshith.droidserve.tv.ui.DsIcons
import com.deekshith.droidserve.tv.ui.DsTextField
import com.deekshith.droidserve.tv.ui.DsType
import com.deekshith.droidserve.tv.ui.InitialFocus
import com.deekshith.droidserve.tv.ui.NavChip
import com.deekshith.droidserve.tv.ui.StateMessage
import com.deekshith.droidserve.tv.ui.VScrollbar
import com.deekshith.droidserve.tv.ui.onMenuKey

/** A saved playlist's channels, opened from the saved list on the main screen. */
@Composable
fun SavedPlaylistScreen(
    state: UiScreen.PlaylistDetail,
    onFilter: (String) -> Unit,
    onPlay: (StreamLink) -> Unit,
    onRemovePlaylist: (String) -> Unit,
    onSaveChannel: (StreamLink) -> Unit,
    onRemoveChannel: (String) -> Unit,
    isChannelSaved: (String) -> Boolean,
) {
    var searchOpen by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }
    var menuOpen by remember { mutableStateOf(false) }
    var focusedLink by remember { mutableStateOf<StreamLink?>(null) }
    val shown = state.shown

    Box(Modifier.fillMaxSize().onMenuKey(enabled = !menuOpen) { menuOpen = true }) {
        Column(
            Modifier.fillMaxSize().background(DsColor.Ink).padding(horizontal = DsDim.ScreenH, vertical = DsDim.ScreenV),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                DsIcon(DsIcons.ListView, tint = DsColor.Amber, size = 24.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.name, style = DsType.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${state.links.size} links", style = DsType.Meta)
                }
                NavChip("Search", DsIcons.Search, active = searchOpen) { searchOpen = !searchOpen }
            }
            if (searchOpen) {
                Spacer(Modifier.height(12.dp))
                DsTextField(state.filter, onFilter, "Search links…", Modifier.width(380.dp).focusRequester(searchFocus))
                LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
            }
            Spacer(Modifier.height(16.dp))
            if (shown.isEmpty()) {
                StateMessage(DsIcons.Link, "No links match", "Try a different search term.")
            } else {
                val listState = rememberLazyListState()
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(shown.size, key = { shown[it].id }) { i ->
                            val l = shown[i]
                            val mod = Modifier
                                .onFocusChanged { if (it.hasFocus) focusedLink = l }
                                .then(if (i == 0) Modifier.focusRequester(firstFocus) else Modifier)
                            LinkRow(l, mod, saved = isChannelSaved(l.id)) { onPlay(l) }
                        }
                    }
                    VScrollbar(listState.layoutInfo.totalItemsCount, listState.layoutInfo.visibleItemsInfo.size, listState.firstVisibleItemIndex)
                }
            }
        }
        if (menuOpen) {
            val actions = buildList {
                focusedLink?.let { l ->
                    if (isChannelSaved(l.id)) add(ContextAction("Remove link from Home", DsIcons.Warning, destructive = true) { onRemoveChannel(l.id) })
                    else add(ContextAction("Save link to Home", DsIcons.Link) { onSaveChannel(l) })
                }
                add(ContextAction("Remove playlist from Home", DsIcons.Warning, destructive = true) { onRemovePlaylist(state.name) })
            }
            ContextMenu(focusedLink?.label ?: state.name, actions) { menuOpen = false }
        }
    }
    InitialFocus(firstFocus, enabled = !searchOpen && shown.isNotEmpty(), state.name, shown.size, searchOpen)
}
