package com.deekshith.droidserve.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deekshith.droidserve.tv.StreamLink
import com.deekshith.droidserve.tv.UiScreen
import com.deekshith.droidserve.tv.ui.Badge
import com.deekshith.droidserve.tv.ui.ContextAction
import com.deekshith.droidserve.tv.ui.ContextMenu
import com.deekshith.droidserve.tv.ui.DsColor
import com.deekshith.droidserve.tv.ui.DsIcon
import com.deekshith.droidserve.tv.ui.DsIcons
import com.deekshith.droidserve.tv.ui.DsLoading
import com.deekshith.droidserve.tv.ui.DsScreen
import com.deekshith.droidserve.tv.ui.DsTextField
import com.deekshith.droidserve.tv.ui.DsType
import com.deekshith.droidserve.tv.ui.FocusRow
import com.deekshith.droidserve.tv.ui.InitialFocus
import com.deekshith.droidserve.tv.ui.NavChip
import com.deekshith.droidserve.tv.ui.StateMessage
import com.deekshith.droidserve.tv.ui.TopBar
import com.deekshith.droidserve.tv.ui.iconForLink
import com.deekshith.droidserve.tv.ui.onMenuKey

@Composable
fun LinksScreen(
    state: UiScreen.Links,
    onOpen: (StreamLink) -> Unit,
    onOpenGroup: (String) -> Unit,
    onSaveGroup: (String) -> Unit,
    onSaveChannel: (StreamLink) -> Unit,
    onRemoveGroup: (String) -> Unit,
    onRemoveChannel: (String) -> Unit,
    isGroupSaved: (String) -> Boolean,
    isChannelSaved: (String) -> Boolean,
) {
    val firstFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    var searchOpen by remember { mutableStateOf(false) }
    var filter by remember(state.links) { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var focusedGroup by remember { mutableStateOf<String?>(null) }
    var focusedLink by remember { mutableStateOf<StreamLink?>(null) }

    val f = filter.trim().lowercase()
    val groups = remember(state.links, f) {
        LinkedHashMap<String, Int>().apply {
            state.links.forEach { l ->
                l.group?.let { g ->
                    if (f.isEmpty() || g.lowercase().contains(f) || l.label.lowercase().contains(f)) put(g, (get(g) ?: 0) + 1)
                }
            }
        }
    }
    val singles = remember(state.links, f) {
        state.links.filter { it.group == null && (f.isEmpty() || it.label.lowercase().contains(f)) }
    }
    val itemCount = groups.size + singles.size

    Box(
        Modifier.fillMaxSize().onMenuKey(enabled = !menuOpen && state.links.isNotEmpty()) {
            if (focusedGroup != null || focusedLink != null) menuOpen = true
        },
    ) {
        DsScreen {
            TopBar(
                "Streaming Links",
                "$itemCount items · press the ☰ menu button to save to Home",
                leadingIcon = DsIcons.Link,
                actions = { NavChip("Search", DsIcons.Search, active = searchOpen) { searchOpen = !searchOpen } },
            )
            if (searchOpen) {
                Spacer(Modifier.height(12.dp))
                DsTextField(filter, { filter = it }, "Search channels & playlists…", Modifier.width(380.dp).focusRequester(searchFocus))
                LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
            }
            Spacer(Modifier.height(16.dp))
            when {
                state.loading -> DsLoading("Loading links…")
                state.error != null -> StateMessage(DsIcons.Warning, "Couldn't load links", state.error, iconTint = DsColor.Coral)
                state.links.isEmpty() -> StateMessage(
                    DsIcons.Link, "No links yet",
                    "On your phone, open DroidServe, tap Links, and paste a video or stream URL — it appears here instantly.",
                )
                itemCount == 0 -> StateMessage(DsIcons.Search, "No matches", "Try a different search term.")
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    val groupKeys = groups.keys.toList()
                    items(groupKeys.size, key = { "grp:" + groupKeys[it] }) { i ->
                        val name = groupKeys[i]
                        val mod = Modifier
                            .onFocusChanged { if (it.hasFocus) { focusedGroup = name; focusedLink = null } }
                            .then(if (i == 0) Modifier.focusRequester(firstFocus) else Modifier)
                        FocusRow(onClick = { onOpenGroup(name) }, modifier = mod) { focused ->
                            DsIcon(DsIcons.ListView, tint = if (focused) DsColor.Amber else DsColor.Muted, size = 26.dp)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, style = DsType.Item, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${groups[name]} channels", style = DsType.Caption)
                            }
                            if (isGroupSaved(name)) { Badge("SAVED"); Spacer(Modifier.width(12.dp)) }
                            DsIcon(DsIcons.Chevron, tint = if (focused) DsColor.Amber else DsColor.Muted, size = 20.dp)
                        }
                    }
                    items(singles.size, key = { singles[it].id }) { i ->
                        val l = singles[i]
                        val firstSingle = groups.isEmpty() && i == 0
                        val mod = Modifier
                            .onFocusChanged { if (it.hasFocus) { focusedLink = l; focusedGroup = null } }
                            .then(if (firstSingle) Modifier.focusRequester(firstFocus) else Modifier)
                        LinkRow(l, mod, saved = isChannelSaved(l.id)) { onOpen(l) }
                    }
                }
            }
        }
        if (menuOpen) {
            val actions = buildList {
                focusedGroup?.let { g ->
                    if (isGroupSaved(g)) add(ContextAction("Remove playlist from Home", DsIcons.Warning, destructive = true) { onRemoveGroup(g) })
                    else add(ContextAction("Save playlist to Home", DsIcons.Link) { onSaveGroup(g) })
                }
                focusedLink?.let { l ->
                    if (isChannelSaved(l.id)) add(ContextAction("Remove channel from Home", DsIcons.Warning, destructive = true) { onRemoveChannel(l.id) })
                    else add(ContextAction("Save channel to Home", DsIcons.Link) { onSaveChannel(l) })
                }
            }
            val title = focusedGroup ?: focusedLink?.label ?: ""
            if (actions.isNotEmpty()) ContextMenu(title, actions) { menuOpen = false } else menuOpen = false
        }
    }
    InitialFocus(firstFocus, enabled = !searchOpen && itemCount > 0, state.links.size, searchOpen, itemCount)
}

@Composable
fun LinkGroupScreen(
    state: UiScreen.LinkGroup,
    onOpen: (StreamLink) -> Unit,
    onSaveGroup: (String) -> Unit,
    onSaveChannel: (StreamLink) -> Unit,
    onRemoveGroup: (String) -> Unit,
    onRemoveChannel: (String) -> Unit,
    isGroupSaved: (String) -> Boolean,
    isChannelSaved: (String) -> Boolean,
) {
    val firstFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    var searchOpen by remember { mutableStateOf(false) }
    var filter by remember(state.group) { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var focusedLink by remember { mutableStateOf<StreamLink?>(null) }

    val f = filter.trim().lowercase()
    val shown = state.links.filter { f.isEmpty() || it.label.lowercase().contains(f) }

    Box(Modifier.fillMaxSize().onMenuKey(enabled = !menuOpen) { menuOpen = true }) {
        DsScreen {
            TopBar(
                state.group,
                "${state.links.size} channels · press the ☰ menu button to save",
                leadingIcon = DsIcons.ListView,
                actions = { NavChip("Search", DsIcons.Search, active = searchOpen) { searchOpen = !searchOpen } },
            )
            if (searchOpen) {
                Spacer(Modifier.height(12.dp))
                DsTextField(filter, { filter = it }, "Search channels…", Modifier.width(380.dp).focusRequester(searchFocus))
                LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
            }
            Spacer(Modifier.height(16.dp))
            when {
                state.links.isEmpty() -> StateMessage(DsIcons.Link, "This playlist is empty", "Its channels may have expired.")
                shown.isEmpty() -> StateMessage(DsIcons.Search, "No matches", "Try a different search term.")
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(shown.size, key = { shown[it].id }) { i ->
                        val l = shown[i]
                        val mod = Modifier
                            .onFocusChanged { if (it.hasFocus) focusedLink = l }
                            .then(if (i == 0) Modifier.focusRequester(firstFocus) else Modifier)
                        LinkRow(l, mod, saved = isChannelSaved(l.id)) { onOpen(l) }
                    }
                }
            }
        }
        if (menuOpen) {
            val actions = buildList {
                if (isGroupSaved(state.group)) add(ContextAction("Remove playlist from Home", DsIcons.Warning, destructive = true) { onRemoveGroup(state.group) })
                else add(ContextAction("Save whole playlist to Home", DsIcons.ListView) { onSaveGroup(state.group) })
                focusedLink?.let { l ->
                    if (isChannelSaved(l.id)) add(ContextAction("Remove channel from Home", DsIcons.Warning, destructive = true) { onRemoveChannel(l.id) })
                    else add(ContextAction("Save channel to Home", DsIcons.Link) { onSaveChannel(l) })
                }
            }
            ContextMenu(focusedLink?.label ?: state.group, actions) { menuOpen = false }
        }
    }
    InitialFocus(firstFocus, enabled = !searchOpen && shown.isNotEmpty(), state.group, state.links.size, searchOpen)
}

@Composable
internal fun LinkRow(l: StreamLink, modifier: Modifier, saved: Boolean = false, onClick: () -> Unit) {
    FocusRow(onClick = onClick, modifier = modifier) { focused ->
        DsIcon(iconForLink(l.url), tint = if (focused) DsColor.Amber else DsColor.Muted, size = 26.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(l.label, style = DsType.Item, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(l.url, style = DsType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (saved) { Badge("SAVED"); Spacer(Modifier.width(12.dp)) }
        DsIcon(DsIcons.Play, tint = if (focused) DsColor.Amber else DsColor.Muted, size = 18.dp)
    }
}
