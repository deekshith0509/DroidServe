package com.deekshith.droidserve.tv

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.deekshith.droidserve.tv.ui.DsColor
import com.deekshith.droidserve.tv.ui.LocalReduceMotion
import com.deekshith.droidserve.tv.ui.screens.AlbumSongsScreen
import com.deekshith.droidserve.tv.ui.screens.ApkInstallScreen
import com.deekshith.droidserve.tv.ui.screens.AudioPlayerScreen
import com.deekshith.droidserve.tv.ui.screens.AuthScreen
import com.deekshith.droidserve.tv.ui.screens.BrowseScreen
import com.deekshith.droidserve.tv.ui.screens.CrashScreen
import com.deekshith.droidserve.tv.ui.screens.DiscoveryScreen
import com.deekshith.droidserve.tv.ui.screens.HomeScreen
import com.deekshith.droidserve.tv.ui.screens.LinkGroupScreen
import com.deekshith.droidserve.tv.ui.screens.LinksScreen
import com.deekshith.droidserve.tv.ui.screens.MusicLibraryScreen
import com.deekshith.droidserve.tv.ui.screens.SavedPlaylistScreen
import com.deekshith.droidserve.tv.ui.screens.ViewerScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: BrowseViewModel by viewModels()

    private companion object { const val REQ_PLAYER = 1001 }

    // The URL of the video we last handed to an external player, so we can attribute the returned
    // resume position to the right file.
    private var lastVideoUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        PlaybackStore.pruneIfLarge(application)

        val crashFile = App.crashFile(application)
        val lastCrash = if (crashFile.exists()) {
            val t = runCatching { crashFile.readText() }.getOrNull()
            runCatching { crashFile.delete() }
            t
        } else null

        lifecycleScope.launch { vm.open.collect { req -> openInDefaultApp(req) } }
        lifecycleScope.launch { vm.install.collect { file -> installApk(file) } }

        val reduceMotion = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

        setContent {
            CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
                var crash by remember { mutableStateOf(lastCrash) }
                val c = crash
                if (c != null) {
                    CrashScreen(c) { crash = null }
                } else {
                    val screen by vm.screen.collectAsStateWithLifecycle()
                    Box(Modifier.fillMaxSize().background(DsColor.Ink)) {
                        when (val s = screen) {
                            is UiScreen.Home -> HomeScreen(s, vm::connectPc, vm::setHomeFilter, vm::openSavedPlaylist, vm::onLinkClick, vm::removeSavedPlaylist, vm::removeSavedChannel)
                            is UiScreen.PlaylistDetail -> SavedPlaylistScreen(s, vm::setPlaylistFilter, vm::onLinkClick, vm::removeSavedPlaylist)
                            is UiScreen.Discovery -> DiscoveryScreen(s, vm::connect, vm::connectManual, vm::rescan)
                            is UiScreen.Auth -> AuthScreen(s, vm::submitAuth, vm::forgetSaved)
                            is UiScreen.Browse -> BrowseScreen(s, vm::onEntryClick, vm::setFilter, vm::setSort, vm::clearCastToast, vm::toggleView, vm::openMusic, vm::openLinks) { vm.openFolder(s.server, s.path) }
                            is UiScreen.Viewer -> ViewerScreen(s)
                            is UiScreen.AudioPlayer -> AudioPlayerScreen(s, vm::audioPrev, vm::audioNext, vm::audioCompleted)
                            is UiScreen.ApkInstall -> ApkInstallScreen(s, vm::installReady)
                            is UiScreen.MusicLibrary -> MusicLibraryScreen(s, vm::openAlbum, vm::setMusicFilter)
                            is UiScreen.AlbumSongs -> AlbumSongsScreen(s, vm::playSong)
                            is UiScreen.Links -> LinksScreen(s, vm::onLinkClick, vm::openLinkGroup, vm::saveLinkGroupToHome, vm::saveChannelToHome, vm::removeSavedPlaylist, vm::removeSavedChannel, vm::isGroupSaved, vm::isChannelSaved)
                            is UiScreen.LinkGroup -> LinkGroupScreen(s, vm::onLinkClick, vm::saveLinkGroupToHome, vm::saveChannelToHome, vm::removeSavedPlaylist, vm::removeSavedChannel, vm::isGroupSaved, vm::isChannelSaved)
                        }
                    }
                }
            }
        }
    }

    /**
     * Delegate playback/viewing to whatever app the user already has (VLC, MX Player, a photo
     * viewer, etc). Hardware decoding and broad codec support for free, and the TV app stays tiny.
     */
    private fun openInDefaultApp(req: OpenRequest) {
        val isVideo = req.mime.startsWith("video/")
        val uri = Uri.parse(req.url)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            if (req.mime.isBlank()) setData(uri) else setDataAndType(uri, req.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            req.subUrl?.let { s ->
                val subUri = Uri.parse(s)
                putExtra("subtitles_location", s)                 // VLC for Android
                putExtra("subs", arrayOf(subUri))                 // MX Player
                putExtra("subs.name", arrayOf("Subtitles"))
            }
            if (req.resumeMs > 0) {
                putExtra("position", req.resumeMs.toLong())       // VLC
                putExtra("return_result", true)                   // MX: ask it to report back
            } else if (isVideo && !req.forceChooser) {
                putExtra("return_result", true)
            }
        }
        if (req.forceChooser) {
            try {
                startActivityForResult(Intent.createChooser(intent, "Open with"), REQ_PLAYER)
            } catch (e: Exception) {
                Toast.makeText(this, "No app can open this link", Toast.LENGTH_LONG).show()
            }
            return
        }
        lastVideoUrl = if (isVideo) req.url else null
        try {
            if (isVideo) startActivityForResult(intent, REQ_PLAYER) else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(intent)
            }
        } catch (e: Exception) {
            try {
                startActivity(Intent.createChooser(intent, "Open with").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e2: Exception) {
                Toast.makeText(this, "No app can open this file", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Hand a downloaded APK to the system installer via a FileProvider content:// URI. On API 26+
    // the app needs the per-source "install unknown apps" grant; if missing, route the user there.
    private fun installApk(file: java.io.File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Toast.makeText(this, "Allow DroidServe to install apps, then press Install", Toast.LENGTH_LONG).show()
                return
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Toast.makeText(this, "Install failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PLAYER) return
        val url = lastVideoUrl ?: return
        val pos = data?.let {
            when {
                it.hasExtra("extra_position") -> it.getLongExtra("extra_position", 0).toInt()  // MX
                it.hasExtra("position") -> it.getLongExtra("position", 0).toInt()               // VLC
                else -> -1
            }
        } ?: -1
        if (pos >= 0) vm.recordPlaybackPosition(url, pos)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!vm.onBack()) super.onBackPressed()
    }
}
