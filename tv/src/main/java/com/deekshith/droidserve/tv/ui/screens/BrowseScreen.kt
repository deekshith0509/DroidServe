package com.deekshith.droidserve.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.deekshith.droidserve.tv.RemoteEntry
import com.deekshith.droidserve.tv.SortMode
import com.deekshith.droidserve.tv.UiScreen
import com.deekshith.droidserve.tv.ui.Backdrop
import com.deekshith.droidserve.tv.ui.Badge
import com.deekshith.droidserve.tv.ui.DsColor
import com.deekshith.droidserve.tv.ui.DsDim
import com.deekshith.droidserve.tv.ui.GridDensity
import com.deekshith.droidserve.tv.ui.densityLabel
import com.deekshith.droidserve.tv.ui.densityTileMin
import com.deekshith.droidserve.tv.ui.DsIcon
import com.deekshith.droidserve.tv.ui.DsIcons
import com.deekshith.droidserve.tv.ui.DsLoading
import com.deekshith.droidserve.tv.ui.DsTextField
import com.deekshith.droidserve.tv.ui.DsType
import com.deekshith.droidserve.tv.ui.FocusRow
import com.deekshith.droidserve.tv.ui.InitialFocus
import com.deekshith.droidserve.tv.ui.MediaTile
import com.deekshith.droidserve.tv.ui.NavChip
import com.deekshith.droidserve.tv.ui.PrimaryButton
import com.deekshith.droidserve.tv.ui.StateMessage
import com.deekshith.droidserve.tv.ui.Thumb
import com.deekshith.droidserve.tv.ui.VScrollbar
import com.deekshith.droidserve.tv.ui.breadcrumb
import com.deekshith.droidserve.tv.ui.formatSize
import com.deekshith.droidserve.tv.ui.iconFor
import com.deekshith.droidserve.tv.ui.metaFor

@Composable
fun BrowseScreen(
    state: UiScreen.Browse,
    onClick: (RemoteEntry) -> Unit,
    onFilter: (String) -> Unit,
    onSort: (SortMode) -> Unit,
    onClearCast: () -> Unit,
    onToggleView: () -> Unit,
    onMusic: () -> Unit,
    onLinks: () -> Unit,
    onCycleDensity: () -> Unit,
    onRetry: () -> Unit,
) {
    val firstItemFocus = remember { FocusRequester() }
    var searchOpen by remember(state.path) { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    // The focused item drives the backdrop + hero info; defaults to the first entry on load.
    var focused by remember(state.path, state.entries) { mutableStateOf(state.entries.firstOrNull()) }
    val backdropUrl = focused?.takeIf { it.isThumbnailable }?.thumbUrl

    BoxWithConstraints(Modifier.fillMaxSize().background(DsColor.Ink)) {
        val heroH = (maxHeight * 0.52f).coerceIn(230.dp, 460.dp)
        Backdrop(backdropUrl, Modifier.fillMaxWidth().height(heroH))

        Column(Modifier.fillMaxSize().padding(horizontal = DsDim.ScreenH, vertical = DsDim.ScreenV)) {
            HeroHeader(
                state = state,
                focused = focused,
                searchOpen = searchOpen,
                onToggleSearch = { searchOpen = !searchOpen },
                onSort = onSort,
                onToggleView = onToggleView,
                onMusic = onMusic,
                onLinks = onLinks,
                onCycleDensity = onCycleDensity,
            )
            if (searchOpen) {
                Spacer(Modifier.height(12.dp))
                DsTextField(state.filter, onFilter, "Filter files in this folder…", Modifier.width(380.dp).focusRequester(searchFocus))
                LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
            }
            state.casting?.let {
                Spacer(Modifier.height(12.dp))
                CastBanner()
                LaunchedEffect(it) { kotlinx.coroutines.delay(4000); onClearCast() }
            }
            Spacer(Modifier.height(14.dp))

            when {
                state.loading -> DsLoading("Loading folder…")
                state.error != null -> {
                    val retryFocus = remember { FocusRequester() }
                    StateMessage(
                        DsIcons.Warning, "Couldn't load this folder", state.error, iconTint = DsColor.Coral,
                        action = { PrimaryButton("Retry", Modifier.focusRequester(retryFocus), icon = DsIcons.Refresh) { onRetry() } },
                    )
                    InitialFocus(retryFocus, enabled = true, state.error)
                }
                state.entries.isEmpty() -> StateMessage(
                    DsIcons.Folder,
                    if (state.total == 0) "This folder is empty" else "Nothing matches your filter",
                    if (state.total == 0) "Add files to the shared folder on your phone." else "Try a different search term.",
                )
                state.gridMode -> {
                    val gridState = rememberLazyGridState()
                    Box(Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(densityTileMin(GridDensity.intValue)),
                    horizontalArrangement = Arrangement.spacedBy(DsDim.Gutter),
                    verticalArrangement = Arrangement.spacedBy(DsDim.Gutter),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 40.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.entries.size, key = { state.entries[it].name + it }) { i ->
                        val e = state.entries[i]
                        val mod = Modifier
                            .onFocusChanged { if (it.hasFocus) focused = e }
                            .then(if (i == 0) Modifier.focusRequester(firstItemFocus) else Modifier)
                        MediaTile(
                            title = e.name,
                            subtitle = metaFor(e),
                            thumbUrl = if (e.isDir) null else e.thumbUrl,
                            fallbackIcon = iconFor(e),
                            aspect = 16f / 10f,
                            modifier = mod,
                        ) { onClick(e) }
                    }
                }
                    VScrollbar(gridState.layoutInfo.totalItemsCount, gridState.layoutInfo.visibleItemsInfo.size, gridState.firstVisibleItemIndex)
                    }
                }
                else -> {
                    val listState = rememberLazyListState()
                    Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 36.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.entries.size, key = { state.entries[it].name + it }) { i ->
                        val e = state.entries[i]
                        val mod = Modifier
                            .onFocusChanged { if (it.hasFocus) focused = e }
                            .then(if (i == 0) Modifier.focusRequester(firstItemFocus) else Modifier)
                        EntryRow(e, mod) { onClick(e) }
                    }
                }
                    VScrollbar(listState.layoutInfo.totalItemsCount, listState.layoutInfo.visibleItemsInfo.size, listState.firstVisibleItemIndex)
                    }
                }
            }
        }
    }
    InitialFocus(firstItemFocus, enabled = !searchOpen && state.entries.isNotEmpty(), state.path, state.entries.size, searchOpen)
}

@Composable
private fun HeroHeader(
    state: UiScreen.Browse,
    focused: RemoteEntry?,
    searchOpen: Boolean,
    onToggleSearch: () -> Unit,
    onSort: (SortMode) -> Unit,
    onToggleView: () -> Unit,
    onMusic: () -> Unit,
    onLinks: () -> Unit,
    onCycleDensity: () -> Unit,
) {
    Column {
        // Row 1: location + primary section nav.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DsIcon(DsIcons.Device, tint = DsColor.Amber, size = 20.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(state.server.displayName, style = DsType.Label.copy(color = DsColor.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NavChip("Search", DsIcons.Search, active = searchOpen) { onToggleSearch() }
                NavChip(if (state.gridMode) "List" else "Grid", if (state.gridMode) DsIcons.ListView else DsIcons.Grid, active = false) { onToggleView() }
                NavChip("Music", DsIcons.Audio, active = false) { onMusic() }
                NavChip("Links", DsIcons.Link, active = false) { onLinks() }
            }
        }
        Spacer(Modifier.height(16.dp))
        // Row 2: the focused item, large (the immersive hero).
        val title = focused?.name ?: (if (state.path.isEmpty()) state.server.displayName else state.path.substringAfterLast('/'))
        Text(title, style = DsType.Hero, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.path.isNotEmpty()) Text(breadcrumb(state.path), style = DsType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            focused?.let { f ->
                if (!f.isDir) Text(formatSize(f.size), style = DsType.Meta)
                Text(heroKind(f), style = DsType.Meta.copy(color = DsColor.Amber))
                if (f.subUrl != null) Badge("CC")
            }
        }
        Spacer(Modifier.height(16.dp))
        // Row 3: sort + count.
        Row(verticalAlignment = Alignment.CenterVertically) {
            NavChip("Recent", null, active = state.sort == SortMode.DEFAULT) { onSort(SortMode.DEFAULT) }
            Spacer(Modifier.width(8.dp))
            NavChip("Name", null, active = state.sort == SortMode.NAME) { onSort(SortMode.NAME) }
            Spacer(Modifier.width(8.dp))
            NavChip("Largest", null, active = state.sort == SortMode.SIZE) { onSort(SortMode.SIZE) }
            if (state.gridMode) {
                Spacer(Modifier.width(8.dp))
                NavChip(densityLabel(GridDensity.intValue), DsIcons.Grid, active = false) { onCycleDensity() }
            }
            Spacer(Modifier.width(16.dp))
            Text(statusLine(state), style = DsType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun heroKind(e: RemoteEntry): String = when {
    e.isDir -> "Folder"
    e.isVideo -> "Video"
    e.isAudio -> "Audio"
    e.isImage -> "Image"
    e.isApk -> "App"
    else -> "File"
}

@Composable
private fun EntryRow(e: RemoteEntry, modifier: Modifier, onClick: () -> Unit) {
    FocusRow(onClick = onClick, modifier = modifier) { focusedRow ->
        if (!e.isDir && e.thumbUrl != null) {
            Thumb(e.thumbUrl, iconFor(e), Modifier.size(52.dp).clip(RoundedCornerShape(9.dp)), iconSize = 24.dp)
        } else {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(9.dp)).background(DsColor.Ink2), contentAlignment = Alignment.Center) {
                DsIcon(iconFor(e), tint = if (focusedRow) DsColor.Amber else DsColor.Muted, size = 26.dp)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, style = DsType.Item, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(metaFor(e), style = DsType.Caption)
        }
        if (e.subUrl != null) { Badge("CC"); Spacer(Modifier.width(12.dp)) }
        DsIcon(if (e.isDir) DsIcons.Chevron else DsIcons.Play, tint = DsColor.Muted, size = if (e.isDir) 20.dp else 18.dp)
    }
}

@Composable
private fun CastBanner() {
    Row(
        Modifier.clip(RoundedCornerShape(DsDim.RadiusChip)).background(DsColor.Amber.copy(alpha = 0.14f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DsIcon(DsIcons.Play, tint = DsColor.Amber, size = 18.dp)
        Spacer(Modifier.width(10.dp))
        Text("Casting from your phone — opening in your player", style = DsType.Meta.copy(color = DsColor.Cream))
    }
}

private fun statusLine(s: UiScreen.Browse): String {
    val base = if (s.filter.isBlank()) "${s.entries.size} items" else "${s.entries.size} of ${s.total} items"
    return "$base   ·   ${s.fileCount} files   ·   ${s.dirCount} folders   ·   ${formatSize(s.totalBytes)}"
}
