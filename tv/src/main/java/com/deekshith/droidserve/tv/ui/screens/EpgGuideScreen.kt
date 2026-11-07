package com.deekshith.droidserve.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.deekshith.droidserve.tv.ui.Thumb
import com.deekshith.droidserve.tv.ui.TopBar
import com.deekshith.droidserve.tv.ui.VScrollbar
import com.deekshith.droidserve.tv.ui.iconForLink

@Composable
fun EpgGuideScreen(state: UiScreen.EpgGuide, onPick: (Int) -> Unit) {
    val firstFocus = remember { FocusRequester() }
    val hasEpg = state.rows.any { it.epg?.hasData == true }
    DsScreen {
        TopBar(
            state.title ?: "TV guide",
            if (state.loading) "Loading guide…" else if (hasEpg) "Now & next · pick a channel to watch" else "No programme guide for this playlist — pick a channel to watch",
            leadingIcon = DsIcons.Info,
        )
        Spacer(Modifier.height(16.dp))
        when {
            state.loading -> DsLoading("Loading guide…")
            state.rows.isEmpty() -> StateMessage(DsIcons.Warning, "No channels", null)
            else -> {
                val listState = rememberLazyListState()
                LaunchedEffect(Unit) { runCatching { listState.scrollToItem(state.currentIndex.coerceAtLeast(0)) } }
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(state.rows.size, key = { state.rows[it].channel.id }) { r ->
                            val row = state.rows[r]
                            val mod = if (r == state.currentIndex) Modifier.focusRequester(firstFocus) else Modifier
                            FocusRow(onClick = { onPick(row.index) }, modifier = mod) { focused ->
                                Text("${row.index + 1}", style = DsType.Caption.copy(color = if (row.index == state.currentIndex) DsColor.Amber else DsColor.Muted),
                                    modifier = Modifier.width(34.dp))
                                if (row.channel.logo != null) {
                                    Thumb(row.channel.logo, iconForLink(row.channel.url), Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)),
                                        fallbackTint = if (focused) DsColor.Amber else DsColor.Muted, iconSize = 20.dp, contentScale = ContentScale.Fit)
                                } else {
                                    DsIcon(iconForLink(row.channel.url), tint = if (focused) DsColor.Amber else DsColor.Muted, size = 24.dp)
                                }
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(row.channel.label, style = DsType.Item, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val nn = row.epg
                                    val sub = when {
                                        nn?.now != null -> "Now: ${nn.now}" + (nn.next?.let { "   ·   Next: $it" } ?: "")
                                        row.channel.group != null -> row.channel.group!!
                                        else -> "—"
                                    }
                                    Text(sub, style = DsType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    nn?.progress(System.currentTimeMillis())?.let { p ->
                                        Spacer(Modifier.height(4.dp))
                                        Box(Modifier.fillMaxWidth().height(2.dp).background(DsColor.Line)) {
                                            Box(Modifier.fillMaxWidth(p).height(2.dp).background(DsColor.Amber))
                                        }
                                    }
                                }
                                if (row.index == state.currentIndex) { Spacer(Modifier.width(10.dp)); DsIcon(DsIcons.Play, tint = DsColor.Amber, size = 16.dp) }
                            }
                        }
                    }
                    VScrollbar(listState.layoutInfo.totalItemsCount, listState.layoutInfo.visibleItemsInfo.size, listState.firstVisibleItemIndex)
                }
            }
        }
    }
    InitialFocus(firstFocus, enabled = !state.loading && state.rows.isNotEmpty(), state.rows.size)
}
