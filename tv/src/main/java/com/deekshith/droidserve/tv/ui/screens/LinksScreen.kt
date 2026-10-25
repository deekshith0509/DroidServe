package com.deekshith.droidserve.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deekshith.droidserve.tv.StreamLink
import com.deekshith.droidserve.tv.UiScreen
import com.deekshith.droidserve.tv.ui.DsColor
import com.deekshith.droidserve.tv.ui.DsIcon
import com.deekshith.droidserve.tv.ui.DsIcons
import com.deekshith.droidserve.tv.ui.DsLoading
import com.deekshith.droidserve.tv.ui.DsScreen
import com.deekshith.droidserve.tv.ui.DsType
import com.deekshith.droidserve.tv.ui.FocusRow
import com.deekshith.droidserve.tv.ui.InitialFocus
import com.deekshith.droidserve.tv.ui.StateMessage
import com.deekshith.droidserve.tv.ui.TopBar
import com.deekshith.droidserve.tv.ui.iconForLink

@Composable
fun LinksScreen(
    state: UiScreen.Links,
    onOpen: (StreamLink) -> Unit,
    onOpenGroup: (String) -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    // Playlist channels (group != null) collapse into one drill-in row per group; the rest are
    // standalone links opened directly.
    val groups = remember(state.links) {
        LinkedHashMap<String, Int>().apply { state.links.forEach { it.group?.let { g -> put(g, (get(g) ?: 0) + 1) } } }
    }
    val singles = remember(state.links) { state.links.filter { it.group == null } }
    val itemCount = groups.size + singles.size

    DsScreen {
        TopBar(
            "Streaming Links",
            "$itemCount items · paste more from the DroidServe web UI on your phone",
            leadingIcon = DsIcons.Link,
        )
        Spacer(Modifier.height(16.dp))
        when {
            state.loading -> DsLoading("Loading links…")
            state.error != null -> StateMessage(DsIcons.Warning, "Couldn't load links", state.error, iconTint = DsColor.Coral)
            state.links.isEmpty() -> StateMessage(
                DsIcons.Link, "No links yet",
                "On your phone, open DroidServe, tap Links, and paste a video or stream URL — it appears here instantly.",
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                val groupKeys = groups.keys.toList()
                items(groupKeys.size, key = { "grp:" + groupKeys[it] }) { i ->
                    val name = groupKeys[i]
                    val mod = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier
                    FocusRow(onClick = { onOpenGroup(name) }, modifier = mod) { focused ->
                        DsIcon(DsIcons.ListView, tint = if (focused) DsColor.Amber else DsColor.Muted, size = 26.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, style = DsType.Item, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${groups[name]} channels", style = DsType.Caption)
                        }
                        DsIcon(DsIcons.Chevron, tint = if (focused) DsColor.Amber else DsColor.Muted, size = 20.dp)
                    }
                }
                items(singles.size, key = { singles[it].id }) { i ->
                    val l = singles[i]
                    val mod = if (groups.isEmpty() && i == 0) Modifier.focusRequester(firstFocus) else Modifier
                    LinkRow(l, mod) { onOpen(l) }
                }
            }
        }
    }
    InitialFocus(firstFocus, enabled = state.links.isNotEmpty(), state.links.size)
}

@Composable
fun LinkGroupScreen(state: UiScreen.LinkGroup, onOpen: (StreamLink) -> Unit) {
    val firstFocus = remember { FocusRequester() }
    DsScreen {
        TopBar(state.group, "${state.links.size} channels", leadingIcon = DsIcons.ListView)
        Spacer(Modifier.height(16.dp))
        if (state.links.isEmpty()) {
            StateMessage(DsIcons.Link, "This playlist is empty", "Its channels may have expired.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                items(state.links.size, key = { state.links[it].id }) { i ->
                    val l = state.links[i]
                    val mod = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier
                    LinkRow(l, mod) { onOpen(l) }
                }
            }
        }
    }
    InitialFocus(firstFocus, enabled = state.links.isNotEmpty(), state.group, state.links.size)
}

@Composable
private fun LinkRow(l: StreamLink, modifier: Modifier, onClick: () -> Unit) {
    FocusRow(onClick = onClick, modifier = modifier) { focused ->
        DsIcon(iconForLink(l.url), tint = if (focused) DsColor.Amber else DsColor.Muted, size = 26.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(l.label, style = DsType.Item, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(l.url, style = DsType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DsIcon(DsIcons.Play, tint = if (focused) DsColor.Amber else DsColor.Muted, size = 18.dp)
    }
}
