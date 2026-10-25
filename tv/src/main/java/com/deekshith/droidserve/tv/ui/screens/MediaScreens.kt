package com.deekshith.droidserve.tv.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deekshith.droidserve.tv.ApkPhase
import com.deekshith.droidserve.tv.UiScreen
import com.deekshith.droidserve.tv.ViewerKind
import com.deekshith.droidserve.tv.ui.DsColor
import com.deekshith.droidserve.tv.ui.DsIcon
import com.deekshith.droidserve.tv.ui.DsIcons
import com.deekshith.droidserve.tv.ui.DsLoading
import com.deekshith.droidserve.tv.ui.DsType
import com.deekshith.droidserve.tv.ui.NavChip
import com.deekshith.droidserve.tv.ui.PrimaryButton
import com.deekshith.droidserve.tv.ui.StateMessage
import com.deekshith.droidserve.tv.ui.Thumb
import com.deekshith.droidserve.tv.ui.fmtTime
import com.deekshith.droidserve.tv.ui.sampleSize
import kotlinx.coroutines.withContext

@Composable
fun ViewerScreen(state: UiScreen.Viewer) {
    Column(Modifier.fillMaxSize().background(DsColor.Ink).padding(horizontal = 44.dp, vertical = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DsIcon(if (state.kind == ViewerKind.IMAGE) DsIcons.Image else DsIcons.Doc, tint = DsColor.Amber, size = 22.dp)
            Spacer(Modifier.width(12.dp))
            Text(state.name, style = DsType.Section, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            HintBack()
        }
        Spacer(Modifier.height(16.dp))
        when {
            state.loading -> DsLoading("Loading…")
            state.error != null -> StateMessage(DsIcons.Warning, "Couldn't open this file", state.error, iconTint = DsColor.Coral)
            state.kind == ViewerKind.TEXT -> Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(DsColor.Ink2).padding(20.dp),
            ) {
                Text(state.text ?: "", style = DsType.Mono, modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
            }
            state.kind == ViewerKind.IMAGE && state.imageUrl != null -> RemoteImage(state.imageUrl)
        }
    }
}

/** Decode a remote image off the main thread (no image library, works on API 21+). */
@Composable
private fun RemoteImage(url: String) {
    var bmp by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                fun openStream() = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 5000; readTimeout = 10000
                }
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                openStream().let { c -> try { c.inputStream.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) } } finally { c.disconnect() } }
                val opts = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 2560)
                    inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                }
                openStream().let { c -> try { c.inputStream.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) } } finally { c.disconnect() } }
            }.getOrNull()
        }
        if (result != null) bmp = result else failed = true
    }
    val b = bmp
    when {
        b != null -> Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        failed -> StateMessage(DsIcons.Image, "Couldn't load image", iconTint = DsColor.Coral)
        else -> DsLoading()
    }
}

@Composable
fun AudioPlayerScreen(
    state: UiScreen.AudioPlayer,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onCompleted: () -> Unit,
) {
    val track = state.current
    var playing by remember { mutableStateOf(true) }
    var positionMs by remember(track.url) { mutableStateOf(0) }
    var durationMs by remember(track.url) { mutableStateOf(0) }
    var failed by remember(track.url) { mutableStateOf(false) }

    val player = remember { android.media.MediaPlayer() }
    DisposableEffect(track.url) {
        failed = false; positionMs = 0; durationMs = 0
        runCatching {
            player.reset()
            player.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC)
            player.setDataSource(track.url)
            player.setOnPreparedListener { mp -> durationMs = mp.duration; mp.start(); playing = true }
            player.setOnCompletionListener { onCompleted() }
            player.setOnErrorListener { _, _, _ -> failed = true; true }
            player.prepareAsync()
        }.onFailure { failed = true }
        onDispose { runCatching { player.reset() } }
    }
    DisposableEffect(Unit) { onDispose { runCatching { player.release() } } }
    LaunchedEffect(track.url, playing) {
        while (playing && !failed) {
            positionMs = runCatching { player.currentPosition }.getOrDefault(positionMs)
            kotlinx.coroutines.delay(500)
        }
    }

    Column(
        Modifier.fillMaxSize().background(DsColor.Ink).padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Thumb(track.thumbUrl, DsIcons.Audio, Modifier.size(200.dp).clip(RoundedCornerShape(18.dp)), fallbackTint = DsColor.Amber, iconSize = 72.dp)
        Spacer(Modifier.height(24.dp))
        Text(track.name, style = DsType.Title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Text("${state.server.displayName}   ·   Track ${state.index + 1} of ${state.queue.size}", style = DsType.Meta)
        Spacer(Modifier.height(24.dp))
        if (failed) {
            StateMessage(DsIcons.Warning, "Couldn't play this file", iconTint = DsColor.Coral)
        } else {
            Text("${fmtTime(positionMs)}   /   ${fmtTime(durationMs)}", style = DsType.Mono.copy(color = DsColor.Amber))
            Spacer(Modifier.height(12.dp))
            val frac = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth(0.62f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(DsColor.Line)) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(DsColor.Amber))
            }
            Spacer(Modifier.height(26.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                NavChip("Prev", DsIcons.Prev, active = false) { if (state.hasPrev) onPrev() }
                PrimaryButton(if (playing) "Pause" else "Play", icon = if (playing) DsIcons.Pause else DsIcons.Play) {
                    if (playing) { runCatching { player.pause() }; playing = false }
                    else { runCatching { player.start() }; playing = true }
                }
                NavChip("Next", DsIcons.Next, active = false) { if (state.hasNext) onNext() }
            }
        }
        Spacer(Modifier.height(20.dp))
        HintBack()
    }
}

@Composable
fun ApkInstallScreen(state: UiScreen.ApkInstall, onInstall: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(DsColor.Ink).padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(96.dp).clip(RoundedCornerShape(24.dp)).background(DsColor.Ink2), contentAlignment = Alignment.Center) {
            DsIcon(DsIcons.Apk, tint = DsColor.Amber, size = 48.dp)
        }
        Spacer(Modifier.height(20.dp))
        Text(state.name, style = DsType.Title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(22.dp))
        when (state.phase) {
            ApkPhase.DOWNLOADING -> {
                DsLoading("Downloading…  ${state.progress}%")
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth(0.5f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(DsColor.Line)) {
                    Box(Modifier.fillMaxWidth(state.progress / 100f).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(DsColor.Amber))
                }
            }
            ApkPhase.READY -> {
                Text("Opening the installer — confirm on screen.", style = DsType.Meta)
                Spacer(Modifier.height(18.dp))
                PrimaryButton("Install", icon = DsIcons.Apk) { onInstall() }
                Spacer(Modifier.height(12.dp))
                Text("If it warns about 'unknown apps', allow DroidServe, then press Install.", style = DsType.Caption)
            }
            ApkPhase.FAILED -> StateMessage(DsIcons.Warning, "Install failed", state.error, iconTint = DsColor.Coral)
        }
        Spacer(Modifier.height(20.dp))
        HintBack()
    }
}

@Composable
internal fun HintBack() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        DsIcon(DsIcons.Back, tint = DsColor.Muted, size = 16.dp)
        Spacer(Modifier.width(6.dp))
        Text("Back to close", style = DsType.Caption)
    }
}
