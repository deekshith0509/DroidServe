package com.deekshith.droidserve.tv.ui.screens

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.compose.ui.viewinterop.AndroidView
import com.deekshith.droidserve.tv.StreamLink
import com.deekshith.droidserve.tv.UiScreen
import com.deekshith.droidserve.tv.ui.ContextAction
import com.deekshith.droidserve.tv.ui.ContextMenu
import com.deekshith.droidserve.tv.ui.DsColor
import com.deekshith.droidserve.tv.ui.DsIcon
import com.deekshith.droidserve.tv.ui.DsIcons
import com.deekshith.droidserve.tv.ui.DsTextField
import com.deekshith.droidserve.tv.ui.DsType
import com.deekshith.droidserve.tv.ui.FocusRow
import com.deekshith.droidserve.tv.ui.InitialFocus
import com.deekshith.droidserve.tv.ui.NavChip
import com.deekshith.droidserve.tv.ui.Thumb
import com.deekshith.droidserve.tv.ui.iconForLink
import kotlinx.coroutines.delay

private const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android TV) DroidServe/ExoPlayer"
private const val MAX_RETRIES = 3

private class Reconnect { var attempts = 0 }

/**
 * Full-screen native IPTV player. One long-lived ExoPlayer plays the current channel; flipping
 * channels swaps the media source (no recreate → instant zap, flat RAM).
 *
 * Remote: **↑/↓** flip channels · **OK/←** channel-list overlay (category tabs + search) ·
 * **→** last channel · **digits** zap · **MENU** audio/subtitle track · video fit · sleep timer ·
 * open externally. Per-channel User-Agent/Referer are set on the data source so header-gated IPTV
 * plays; dropped streams auto-reconnect; an optional now/next EPG bar shows when a guide exists.
 */
@OptIn(UnstableApi::class)
@Composable
fun ChannelPlayerScreen(
    state: UiScreen.ChannelPlayer,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onJump: (Int) -> Unit,
    onOverlay: (Boolean) -> Unit,
    onDigit: (Int) -> Unit,
    onExternal: () -> Unit,
    onPreviousChannel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val exo = remember { ExoPlayer.Builder(context).build().apply { playWhenReady = true } }
    val recon = remember { Reconnect() }
    val handler = remember { Handler(Looper.getMainLooper()) }
    var playerState by remember { mutableIntStateOf(Player.STATE_IDLE) }
    var failed by remember { mutableStateOf(false) }
    var tracks by remember { mutableStateOf<Tracks?>(null) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var menuOpen by remember { mutableStateOf(false) }
    var sleepMin by remember { mutableIntStateOf(0) }
    var slept by remember { mutableStateOf(false) }

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                playerState = s
                if (s == Player.STATE_READY) { recon.attempts = 0; failed = false }
            }
            override fun onPlayerError(e: PlaybackException) {
                // Live IPTV streams drop constantly — re-prepare with backoff before giving up.
                if (recon.attempts < MAX_RETRIES) {
                    recon.attempts++
                    handler.postDelayed({ runCatching { exo.prepare(); exo.play() } }, 1200L * recon.attempts)
                } else failed = true
            }
            override fun onTracksChanged(t: Tracks) { tracks = t }
        }
        exo.addListener(listener)
        onDispose { handler.removeCallbacksAndMessages(null); exo.removeListener(listener); exo.release() }
    }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> exo.pause()
                Lifecycle.Event.ON_START -> if (!slept) exo.play()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val current = state.current
    LaunchedEffect(current.id) {
        failed = false; slept = false; recon.attempts = 0
        handler.removeCallbacksAndMessages(null)
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(current.userAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_UA)
            .setAllowCrossProtocolRedirects(true)
        current.referrer?.takeIf { it.isNotBlank() }?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }
        exo.setMediaSource(DefaultMediaSourceFactory(http).createMediaSource(MediaItem.fromUri(current.url)))
        exo.prepare(); exo.playWhenReady = true
    }

    // Sleep timer: after the chosen delay, pause and show a resume prompt. One-shot.
    LaunchedEffect(sleepMin) {
        if (sleepMin <= 0) return@LaunchedEffect
        delay(sleepMin * 60_000L)
        exo.pause(); slept = true
    }

    val playerFocus = remember { FocusRequester() }
    val overlayFocus = remember { FocusRequester() }
    LaunchedEffect(state.overlayOpen, menuOpen) {
        if (!state.overlayOpen && !menuOpen) runCatching { playerFocus.requestFocus() }
    }

    var infoVisible by remember { mutableStateOf(true) }
    LaunchedEffect(state.index, state.epg) { infoVisible = true; delay(6000); infoVisible = false }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(playerFocus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (state.overlayOpen || menuOpen || ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val code = ev.nativeKeyEvent.keyCode
                if (slept) {
                    return@onPreviewKeyEvent when (code) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_PLAY -> { slept = false; exo.play(); true }
                        else -> false
                    }
                }
                digitOf(code)?.let { onDigit(it); return@onPreviewKeyEvent true }
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { onPrev(); true }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { onNext(); true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_LEFT -> { onOverlay(true); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { onPreviousChannel(); true }
                    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_MODE -> { menuOpen = true; true }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE ->
                        { if (exo.isPlaying) exo.pause() else exo.play(); true }
                    else -> false
                }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    player = exo
                    isFocusable = false
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { it.resizeMode = resizeMode },
        )

        if (playerState == Player.STATE_BUFFERING && !failed && !slept) {
            CircularProgressIndicator(color = DsColor.Amber, modifier = Modifier.align(Alignment.Center).size(48.dp))
        }

        if (slept) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                DsIcon(DsIcons.Play, tint = DsColor.Amber, size = 40.dp)
                Spacer(Modifier.height(10.dp))
                Text("Sleep timer — press OK to resume", style = DsType.Title)
            }
        } else if (failed) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                DsIcon(DsIcons.Warning, tint = DsColor.Coral, size = 40.dp)
                Spacer(Modifier.height(10.dp))
                Text("Can't play “${current.label}”", style = DsType.Title)
                Spacer(Modifier.height(4.dp))
                Text("Press ↑/↓ for another channel, or MENU → open externally.", style = DsType.Meta)
            }
        }

        if (state.numberInput.isNotEmpty()) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(36.dp)
                    .background(DsColor.Ink.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 22.dp, vertical = 12.dp),
            ) { Text(state.numberInput, style = DsType.Hero.copy(color = DsColor.Amber)) }
        }

        if ((infoVisible || state.overlayOpen) && !slept) {
            ChannelInfoBar(state, Modifier.align(Alignment.BottomStart))
        }

        if (state.overlayOpen) {
            ChannelOverlay(state, overlayFocus, onJump)
        }
    }

    if (menuOpen) {
        ContextMenu("Player", playerMenuActions(exo, tracks, resizeMode, sleepMin, { resizeMode = it }, { sleepMin = it; slept = false }, onExternal), onDismiss = { menuOpen = false })
    }

    LaunchedEffect(Unit) { runCatching { playerFocus.requestFocus() } }
}

@OptIn(UnstableApi::class)
private fun playerMenuActions(
    exo: ExoPlayer,
    tracks: Tracks?,
    resizeMode: Int,
    sleepMin: Int,
    onResize: (Int) -> Unit,
    onSleep: (Int) -> Unit,
    onExternal: () -> Unit,
): List<ContextAction> = buildList {
    val groups = tracks?.groups ?: emptyList()
    val audio = groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    if (audio.sumOf { it.length } > 1) {
        audio.forEach { g ->
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val sel = g.isTrackSelected(i)
                add(ContextAction((if (sel) "● " else "") + "Audio · " + trackName(g.getTrackFormat(i), i), DsIcons.Play) {
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                })
            }
        }
    }
    val text = groups.filter { it.type == C.TRACK_TYPE_TEXT }
    if (text.isNotEmpty()) {
        add(ContextAction("Subtitles · Off", DsIcons.ListView) {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        })
        text.forEach { g ->
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val sel = g.isTrackSelected(i)
                add(ContextAction((if (sel) "● " else "") + "Subtitle · " + trackName(g.getTrackFormat(i), i), DsIcons.ListView) {
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                })
            }
        }
    }
    add(ContextAction("Video fit · ${fitName(resizeMode)}", DsIcons.ListView) { onResize(nextFit(resizeMode)) })
    add(ContextAction("Sleep timer · ${sleepName(sleepMin)}", DsIcons.Chevron) { onSleep(nextSleep(sleepMin)) })
    add(ContextAction("Open in external player", DsIcons.Link) { onExternal() })
}

@Composable
private fun ChannelInfoBar(state: UiScreen.ChannelPlayer, modifier: Modifier) {
    val ch = state.current
    val epg = state.epg
    Column(
        modifier.fillMaxWidth().background(DsColor.Ink.copy(alpha = 0.82f)).padding(horizontal = 36.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${state.index + 1}", style = DsType.Title.copy(color = DsColor.Amber))
            Spacer(Modifier.width(14.dp))
            if (ch.logo != null) {
                Thumb(ch.logo, iconForLink(ch.url), Modifier.size(34.dp).clip(RoundedCornerShape(6.dp)),
                    fallbackTint = DsColor.Muted, iconSize = 20.dp, contentScale = ContentScale.Fit)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(ch.label, style = DsType.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = when {
                    epg?.now != null -> "Now: ${epg.now}" + (epg.next?.let { "   ·   Next: $it" } ?: "")
                    ch.group != null -> ch.group
                    else -> "Channel ${state.index + 1} of ${state.channels.size}"
                }
                Text(sub, style = DsType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("OK list · ↑↓ zap · → last · ☰ menu", style = DsType.Caption)
        }
        epg?.progress(System.currentTimeMillis())?.let { p ->
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).background(DsColor.Line)) {
                Box(Modifier.fillMaxWidth(p).height(3.dp).background(DsColor.Amber))
            }
        }
    }
}

@Composable
private fun ChannelOverlay(state: UiScreen.ChannelPlayer, firstFocus: FocusRequester, onJump: (Int) -> Unit) {
    var category by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val categories = remember(state.channels) { state.channels.mapNotNull { it.group }.distinct() }
    val f = query.trim().lowercase()
    val filtered = remember(state.channels, category, f) {
        state.channels.withIndex().filter { (_, ch) ->
            (category == null || ch.group == category) && (f.isEmpty() || ch.label.lowercase().contains(f))
        }
    }
    val focusRow = filtered.indexOfFirst { it.index == state.index }.let { if (it >= 0) it else 0 }
    val listState = rememberLazyListState()
    LaunchedEffect(category, f) { runCatching { listState.scrollToItem(focusRow.coerceAtLeast(0)) } }

    Box(Modifier.fillMaxHeight().width(420.dp).background(DsColor.Ink.copy(alpha = 0.96f))) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            DsTextField(query, { query = it }, "Search channels…", Modifier.fillMaxWidth())
            if (categories.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { NavChip("All", null, active = category == null) { category = null } }
                    items(categories.size) { i ->
                        val c = categories[i]
                        NavChip(c, null, active = category == c) { category = c }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (filtered.isEmpty()) {
                Text("No channels match", style = DsType.Meta)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 6.dp),
                ) {
                    items(filtered.size, key = { filtered[it].value.id }) { r ->
                        val gi = filtered[r].index
                        val ch = filtered[r].value
                        val mod = if (r == focusRow) Modifier.focusRequester(firstFocus) else Modifier
                        FocusRow(onClick = { onJump(gi) }, modifier = mod) { focused ->
                            Text("${gi + 1}", style = DsType.Caption.copy(color = if (gi == state.index) DsColor.Amber else DsColor.Muted),
                                modifier = Modifier.width(34.dp))
                            if (ch.logo != null) {
                                Thumb(ch.logo, iconForLink(ch.url), Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)),
                                    fallbackTint = if (focused) DsColor.Amber else DsColor.Muted, iconSize = 18.dp, contentScale = ContentScale.Fit)
                            } else {
                                DsIcon(iconForLink(ch.url), tint = if (focused) DsColor.Amber else DsColor.Muted, size = 22.dp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(ch.label, style = DsType.Item, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (gi == state.index) DsIcon(DsIcons.Play, tint = DsColor.Amber, size = 16.dp)
                        }
                    }
                }
            }
        }
    }
    InitialFocus(firstFocus, enabled = filtered.isNotEmpty(), state.index, category, f)
}

@OptIn(UnstableApi::class)
private fun trackName(f: Format, i: Int): String {
    val lang = f.language?.let { langLabel(it) }
    return lang ?: f.label ?: "Track ${i + 1}"
}

private fun langLabel(code: String): String = when (code.lowercase().take(3)) {
    "tel" -> "Telugu"; "hin" -> "Hindi"; "eng" -> "English"; "tam" -> "Tamil"
    "kan" -> "Kannada"; "mal" -> "Malayalam"; "ben" -> "Bengali"; "mar" -> "Marathi"
    "guj" -> "Gujarati"; "urd" -> "Urdu"; "pan" -> "Punjabi"; "ori" -> "Odia"
    else -> code.uppercase()
}

@OptIn(UnstableApi::class)
private fun fitName(mode: Int): String = when (mode) {
    AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Fill"
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
    else -> "Fit"
}

@OptIn(UnstableApi::class)
private fun nextFit(mode: Int): Int = when (mode) {
    AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
}

private fun sleepName(min: Int): String = if (min <= 0) "Off" else "$min min"
private fun nextSleep(min: Int): Int = when (min) { 0 -> 15; 15 -> 30; 30 -> 60; else -> 0 }

private fun digitOf(code: Int): Int? = when (code) {
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
    else -> null
}
