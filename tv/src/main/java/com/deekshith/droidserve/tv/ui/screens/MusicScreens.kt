package com.deekshith.droidserve.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deekshith.droidserve.tv.MusicAlbum
import com.deekshith.droidserve.tv.UiScreen
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
import com.deekshith.droidserve.tv.ui.VScrollbar
import com.deekshith.droidserve.tv.ui.NavChip
import com.deekshith.droidserve.tv.ui.StateMessage
import com.deekshith.droidserve.tv.ui.Thumb
import com.deekshith.droidserve.tv.ui.TopBar

@Composable
fun MusicLibraryScreen(
    state: UiScreen.MusicLibrary,
    onAlbum: (MusicAlbum) -> Unit,
    onFilter: (String) -> Unit,
) {
    var searchOpen by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }
    DsScreen {
        TopBar(
            "Music",
            "${state.albums.size} albums · ${state.albums.sumOf { it.songs.size }} songs",
            leadingIcon = DsIcons.Audio,
            actions = { NavChip("Search", DsIcons.Search, active = searchOpen) { searchOpen = !searchOpen } },
        )
        if (searchOpen) {
            Spacer(Modifier.height(12.dp))
            DsTextField(state.filter, onFilter, "Search songs or albums…", Modifier.width(380.dp).focusRequester(searchFocus))
            androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
        }
        Spacer(Modifier.height(16.dp))
        when {
            state.loading -> DsLoading("Loading your music…")
            state.error != null -> StateMessage(DsIcons.Warning, "Couldn't load music", state.error, iconTint = DsColor.Coral)
            state.albums.isEmpty() -> StateMessage(DsIcons.Audio, "No music yet", "Add audio to the shared folder on your phone.")
            else -> {
                val shown = state.shown
                val gridState = rememberLazyGridState()
                Box(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(densityTileMin(GridDensity.intValue)),
                    horizontalArrangement = Arrangement.spacedBy(DsDim.Gutter),
                    verticalArrangement = Arrangement.spacedBy(DsDim.Gutter),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 28.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(shown.size, key = { shown[it].path + it }) { i ->
                        val alb = shown[i]
                        val mod = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier
                        MediaTile(
                            title = alb.name,
                            subtitle = "${alb.songs.size} songs",
                            thumbUrl = alb.cover,
                            fallbackIcon = DsIcons.Audio,
                            aspect = 1f,
                            modifier = mod,
                        ) { onAlbum(alb) }
                    }
                }
                VScrollbar(gridState.layoutInfo.totalItemsCount, gridState.layoutInfo.visibleItemsInfo.size, gridState.firstVisibleItemIndex)
                }
            }
        }
    }
    InitialFocus(firstFocus, enabled = !searchOpen && state.albums.isNotEmpty(), state.albums.size, searchOpen)
}

@Composable
fun AlbumSongsScreen(state: UiScreen.AlbumSongs, onPlay: (MusicAlbum, Int) -> Unit) {
    val album = state.album
    val firstFocus = remember { FocusRequester() }
    DsScreen {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Thumb(album.cover, DsIcons.Audio, Modifier.size(84.dp).clip(RoundedCornerShape(14.dp)), fallbackTint = DsColor.Amber, iconSize = 40.dp)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(album.name, style = DsType.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text("${album.songs.size} songs", style = DsType.Meta)
            }
        }
        Spacer(Modifier.height(18.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            items(album.songs.size, key = { album.songs[it].name + it }) { i ->
                val s = album.songs[i]
                val mod = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier
                FocusRow(onClick = { onPlay(album, i) }, modifier = mod) { focused ->
                    Text("${i + 1}".padStart(2, '0'), style = DsType.Caption.copy(color = if (focused) DsColor.Amber else DsColor.Muted))
                    Spacer(Modifier.width(16.dp))
                    Text(s.name, style = DsType.Item, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    DsIcon(DsIcons.Play, tint = if (focused) DsColor.Amber else DsColor.Muted, size = 18.dp)
                }
            }
        }
    }
    InitialFocus(firstFocus, enabled = album.songs.isNotEmpty(), album.path)
}
