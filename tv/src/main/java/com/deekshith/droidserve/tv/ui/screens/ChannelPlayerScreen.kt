package com.deekshith.droidserve.tv.ui.screens

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
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.compose.ui.viewinterop.AndroidView
import com.deekshith.droidserve.tv.StreamLink
import com.deekshith.droidserve.tv.UiScreen
import com.deekshith.droidserve.tv.ui.DsColor
import com.deekshith.droidserve.tv.ui.DsIcon
import com.deekshith.droidserve.tv.ui.DsIcons
import com.deekshith.droidserve.tv.ui.DsType
import com.deekshith.droidserve.tv.ui.FocusRow
import com.deekshith.droidserve.tv.ui.Thumb
import com.deekshith.droidserve.tv.ui.iconForLink
import kotlinx.coroutines.delay

private const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android TV) DroidServe/ExoPlayer"

/**
 * Full-screen native IPTV player. One long-lived ExoPlayer plays the current channel; flipping
 * channels swaps the media source (no recreate → instant zap, flat RAM). D-pad up/down flips
 * channels, OK/left opens the channel-list overlay, digits zap to a number, MENU hands the current
 * channel to an external player. Per-channel User-Agent/Referer are set on the data source so
 * header-gated IPTV streams play. An optional now/next EPG bar shows when a guide is available.
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
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val exo = remember { ExoPlayer.Builder(context).build().apply { playWhenReady = true } }
    var playerState by remember { mutableIntStateOf(Player.STATE_IDLE) }
    var failed by remember { mutableStateOf(false) }

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) { playerState = s }
            override fun onPlayerError(e: PlaybackException) { failed = true }
        }
        exo.addListener(listener)
        onDispose { exo.removeListener(listener); exo.release() }
    }

    // Pause in the background (save bandwidth/CPU); resume on return. No teardown → no RAM churn.
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> exo.pause()
                Lifecycle.Event.ON_START -> exo.play()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val current = state.current
    LaunchedEffect(current.id) {
        failed = false
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(current.userAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_UA)
            .setAllowCrossProtocolRedirects(true)
        current.referrer?.takeIf { it.isNotBlank() }?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }
        val source = DefaultMediaSourceFactory(http).createMediaSource(MediaItem.fromUri(current.url))
        exo.setMediaSource(source)
        exo.prepare()
        exo.playWhenReady = true
    }

    val playerFocus = remember { FocusRequester() }
    val overlayFocus = remember { FocusRequester() }
    LaunchedEffect(state.overlayOpen) {
        runCatching { if (state.overlayOpen) overlayFocus.requestFocus() else playerFocus.requestFocus() }
    }

    // Info bar shows on channel change / EPG arrival, then auto-hides. Stays up while the overlay is open.
    var infoVisible by remember { mutableStateOf(true) }
    LaunchedEffect(state.index, state.epg) { infoVisible = true; delay(6000); infoVisible = false }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(playerFocus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (state.overlayOpen || ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val code = ev.nativeKeyEvent.keyCode
                digitOf(code)?.let { onDigit(it); return@onPreviewKeyEvent true }
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { onPrev(); true }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { onNext(); true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { onOverlay(true); true }
                    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_MODE -> { onExternal(); true }
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
        )

        if (playerState == Player.STATE_BUFFERING && !failed) {
            CircularProgressIndicator(color = DsColor.Amber, modifier = Modifier.align(Alignment.Center).size(48.dp))
        }

        if (failed) {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DsIcon(DsIcons.Warning, tint = DsColor.Coral, size = 40.dp)
                Spacer(Modifier.height(10.dp))
                Text("Can't play “${current.label}”", style = DsType.Title)
                Spacer(Modifier.height(4.dp))
                Text("Press ↑/↓ for another channel, or MENU to open in an external player.", style = DsType.Meta)
            }
        }

        if (state.numberInput.isNotEmpty()) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(36.dp)
                    .background(DsColor.Ink.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 22.dp, vertical = 12.dp),
            ) { Text(state.numberInput, style = DsType.Hero.copy(color = DsColor.Amber)) }
        }

        if (infoVisible || state.overlayOpen) {
            ChannelInfoBar(state, Modifier.align(Alignment.BottomStart))
        }

        if (state.overlayOpen) {
            ChannelOverlay(state, overlayFocus, onJump) { onOverlay(false) }
        }
    }

    LaunchedEffect(Unit) { runCatching { playerFocus.requestFocus() } }
}

@Composable
private fun ChannelInfoBar(state: UiScreen.ChannelPlayer, modifier: Modifier) {
    val ch = state.current
    val epg = state.epg
    Column(
        modifier
            .fillMaxWidth()
            .background(DsColor.Ink.copy(alpha = 0.82f))
            .padding(horizontal = 36.dp, vertical = 18.dp),
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
            Text("OK list · ↑↓ zap · ☰ ext", style = DsType.Caption)
        }
        // Thin progress line through the current programme, when the guide gives a window.
        epg?.progress(System.currentTimeMillis())?.let { p ->
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).background(DsColor.Line)) {
                Box(Modifier.fillMaxWidth(p).height(3.dp).background(DsColor.Amber))
            }
        }
    }
}

@Composable
private fun ChannelOverlay(
    state: UiScreen.ChannelPlayer,
    firstFocus: FocusRequester,
    onJump: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { runCatching { listState.scrollToItem(state.index.coerceAtLeast(0)) } }
    Box(
        Modifier
            .fillMaxHeight()
            .width(380.dp)
            .background(DsColor.Ink.copy(alpha = 0.95f))
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown &&
                    (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                        ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK)
                ) { onClose(); true } else false
            },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 22.dp),
        ) {
            items(state.channels.size, key = { state.channels[it].id }) { i ->
                val ch = state.channels[i]
                val mod = if (i == state.index) Modifier.focusRequester(firstFocus) else Modifier
                FocusRow(onClick = { onJump(i) }, modifier = mod) { focused ->
                    Text("${i + 1}", style = DsType.Caption.copy(color = if (i == state.index) DsColor.Amber else DsColor.Muted),
                        modifier = Modifier.width(34.dp))
                    if (ch.logo != null) {
                        Thumb(ch.logo, iconForLink(ch.url), Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)),
                            fallbackTint = if (focused) DsColor.Amber else DsColor.Muted, iconSize = 18.dp, contentScale = ContentScale.Fit)
                    } else {
                        DsIcon(iconForLink(ch.url), tint = if (focused) DsColor.Amber else DsColor.Muted, size = 22.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(ch.label, style = DsType.Item, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    if (i == state.index) DsIcon(DsIcons.Play, tint = DsColor.Amber, size = 16.dp)
                }
            }
        }
    }
}

private fun digitOf(code: Int): Int? = when (code) {
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
    else -> null
}
