package com.deekshith.droidserve.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deekshith.droidserve.tv.CatalogTab
import com.deekshith.droidserve.tv.IptvCatalog
import com.deekshith.droidserve.tv.UiScreen
import com.deekshith.droidserve.tv.ui.DsColor
import com.deekshith.droidserve.tv.ui.DsIcon
import com.deekshith.droidserve.tv.ui.DsIcons
import com.deekshith.droidserve.tv.ui.DsLoading
import com.deekshith.droidserve.tv.ui.DsScreen
import com.deekshith.droidserve.tv.ui.DsType
import com.deekshith.droidserve.tv.ui.FocusRow
import com.deekshith.droidserve.tv.ui.InitialFocus
import com.deekshith.droidserve.tv.ui.NavChip
import com.deekshith.droidserve.tv.ui.StateMessage
import com.deekshith.droidserve.tv.ui.TopBar
import com.deekshith.droidserve.tv.ui.VScrollbar

@Composable
fun DiscoverScreen(
    state: UiScreen.Catalog,
    onTab: (CatalogTab) -> Unit,
    onPick: (IptvCatalog.Entry) -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize()) {
        DsScreen {
            TopBar(
                "Discover channels",
                "Free IPTV bundles from iptv-org — pick a language, country or category",
                leadingIcon = DsIcons.Grid,
            )
            Spacer(Modifier.height(16.dp))
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NavChip("Featured", DsIcons.Play, active = state.tab == CatalogTab.FEATURED) { onTab(CatalogTab.FEATURED) }
                NavChip("Languages", DsIcons.ListView, active = state.tab == CatalogTab.LANGUAGES) { onTab(CatalogTab.LANGUAGES) }
                NavChip("Countries", DsIcons.Device, active = state.tab == CatalogTab.COUNTRIES) { onTab(CatalogTab.COUNTRIES) }
                NavChip("Categories", DsIcons.Grid, active = state.tab == CatalogTab.CATEGORIES) { onTab(CatalogTab.CATEGORIES) }
            }
            Spacer(Modifier.height(16.dp))
            when {
                state.entries.isEmpty() && state.loading -> DsLoading("Loading catalog…")
                state.entries.isEmpty() && state.error != null -> StateMessage(DsIcons.Warning, "Couldn't load", state.error, iconTint = DsColor.Coral)
                state.entries.isEmpty() -> StateMessage(DsIcons.Search, "Nothing here", "Try another tab.")
                else -> {
                    val listState = rememberLazyListState()
                    LaunchedEffect(state.tab) { runCatching { listState.scrollToItem(0) } }
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(state.entries.size, key = { state.entries[it].url }) { i ->
                                val e = state.entries[i]
                                val mod = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier
                                FocusRow(onClick = { onPick(e) }, modifier = mod) { focused ->
                                    DsIcon(DsIcons.Play, tint = if (focused) DsColor.Amber else DsColor.Muted, size = 22.dp)
                                    Spacer(Modifier.width(14.dp))
                                    Text(e.label, style = DsType.Item, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f))
                                    DsIcon(DsIcons.Chevron, tint = if (focused) DsColor.Amber else DsColor.Muted, size = 18.dp)
                                }
                            }
                        }
                        VScrollbar(listState.layoutInfo.totalItemsCount, listState.layoutInfo.visibleItemsInfo.size, listState.firstVisibleItemIndex)
                    }
                }
            }
        }
        if (state.loadingBundle != null) {
            Column(
                Modifier.fillMaxSize().background(DsColor.Ink.copy(alpha = 0.82f)),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = DsColor.Amber, modifier = Modifier.width(44.dp).height(44.dp))
                Spacer(Modifier.height(14.dp))
                Text("Loading ${state.loadingBundle}…", style = DsType.Body)
            }
        }
    }
    InitialFocus(firstFocus, enabled = state.entries.isNotEmpty(), state.tab, state.entries.size)
}
