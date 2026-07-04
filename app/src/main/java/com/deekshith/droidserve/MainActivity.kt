package com.deekshith.droidserve

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = DroidServeColors) {
                DroidServeApp()
            }
        }
    }
}

// Branded dark palette — matches the in-app icon and the web file-browser UI
private val DroidServeColors = darkColorScheme(
    primary              = Color(0xFF38BDF8),
    onPrimary            = Color(0xFF06283D),
    secondary            = Color(0xFF0EA5E9),
    secondaryContainer   = Color(0xFF1E3A5F),
    onSecondaryContainer = Color(0xFFE2E8F0),
    background           = Color(0xFF0F172A),
    onBackground         = Color(0xFFE2E8F0),
    surface              = Color(0xFF1E293B),
    onSurface            = Color(0xFFE2E8F0),
    surfaceVariant       = Color(0xFF334155),
    outline              = Color(0xFF64748B),
    error                = Color(0xFFEF4444),
    onError              = Color(0xFF1A0000)
)

private const val KEY_AUTO_START = "auto_start"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DroidServeApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val prefs = remember { context.getSharedPreferences(ServerForegroundService.PREF_FILE, Context.MODE_PRIVATE) }

    val isRunning    by ServerStateHolder.isRunning.collectAsState()
    val serverIp     by ServerStateHolder.ip.collectAsState()
    val serverPort   by ServerStateHolder.port.collectAsState()
    val requestCount by ServerStateHolder.requestCount.collectAsState()
    val logEntries   by ServerLog.entries.collectAsState()

    var selectedUri  by remember { mutableStateOf(loadSavedUri(context)) }
    var selectedPath by remember { mutableStateOf(displayPath(context)) }

    // ── Restored settings (F1: remember last-used) ─────────────────────────────
    var portText     by remember { mutableStateOf(prefs.getInt(ServerForegroundService.KEY_PORT, 8080).toString()) }
    var portError    by remember { mutableStateOf(false) }
    var password     by remember { mutableStateOf(prefs.getString(ServerForegroundService.KEY_PASSWORD, "") ?: "") }
    var showPass     by remember { mutableStateOf(false) }

    // ── Options (persisted on change) ──────────────────────────────────────────
    var username      by remember { mutableStateOf(prefs.getString(ServerForegroundService.KEY_USERNAME, "") ?: "") }
    var title         by remember { mutableStateOf(prefs.getString(ServerForegroundService.KEY_TITLE, "DroidServe") ?: "DroidServe") }
    var showHidden    by remember { mutableStateOf(prefs.getBoolean(ServerForegroundService.KEY_SHOW_HIDDEN, false)) }
    var allowZip      by remember { mutableStateOf(prefs.getBoolean(ServerForegroundService.KEY_ALLOW_ZIP, true)) }
    var allowDownload by remember { mutableStateOf(prefs.getBoolean(ServerForegroundService.KEY_ALLOW_DL, true)) }
    var keepAwake     by remember { mutableStateOf(prefs.getBoolean(ServerForegroundService.KEY_KEEP_AWAKE, true)) }
    var autoStopMin   by remember { mutableStateOf(prefs.getInt(ServerForegroundService.KEY_AUTO_STOP, 0)) }
    var autoStart     by remember { mutableStateOf(prefs.getBoolean(KEY_AUTO_START, false)) }

    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val url = if (isRunning && serverIp.isNotEmpty()) "http://$serverIp:$serverPort" else ""

    // 1-second ticker so uptime / live stats refresh while running
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(isRunning) { while (isRunning) { delay(1000); tick++ } }

    fun savePrefs(block: android.content.SharedPreferences.Editor.() -> Unit) = prefs.edit().apply(block).apply()

    fun beginStart() {
        val uri = selectedUri ?: run {
            scope.launch { snackbar.showSnackbar("Please select a folder first") }; return
        }
        val portInt = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: run {
            scope.launch { snackbar.showSnackbar("Enter a valid port (1–65535)") }; return
        }
        startServer(
            context, uri, portInt,
            password.ifBlank { null }, username.ifBlank { null },
            showHidden, allowZip, allowDownload, title.ifBlank { "DroidServe" },
            keepAwake, autoStopMin
        )
    }

    // ── Start-failure detection (grace period) ─────────────────────────────────
    var startAttempted by remember { mutableStateOf(false) }
    LaunchedEffect(startAttempted, isRunning) {
        when {
            isRunning -> startAttempted = false
            startAttempted -> {
                delay(3000)
                if (!isRunning) snackbar.showSnackbar("Failed to start — port may be in use")
                startAttempted = false
            }
        }
    }

    // ── Notification permission (Android 13+) ──────────────────────────────────
    val notifPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // ── Reconcile UI state with the real service on launch / resume ─────────────
    // If Android recreated the process while the foreground service kept running, the
    // in-memory ServerStateHolder flag is false even though the server is alive. Ask the
    // service to re-publish its true state (or shut down if it's a zombie).
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START &&
                !ServerStateHolder.isRunning.value && isServiceRunning(context)
            ) {
                context.startService(Intent(context, ServerForegroundService::class.java).apply {
                    action = ServerForegroundService.ACTION_SYNC
                })
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── Battery-optimization exemption ─────────────────────────────────────────
    // Aggressive OEM ROMs (Infinix/XOS, MIUI, etc.) freeze background processes and kill
    // the socket even with a foreground service, so downloads stall once the app is
    // backgrounded. Requesting the exemption keeps the server responsive.
    var batteryExempt by remember { mutableStateOf(isBatteryExempt(context)) }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        batteryExempt = isBatteryExempt(context)
    }

    // ── Auto-start (F2) ────────────────────────────────────────────────────────
    var autoStartDone by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (autoStart && !autoStartDone && !isRunning && selectedUri != null) {
            autoStartDone = true
            startAttempted = true
            beginStart()
        }
    }

    // QR generation off main thread
    LaunchedEffect(url) {
        qrBitmap = if (url.isNotEmpty()) withContext(Dispatchers.Default) { generateQrBitmap(url, 512) } else null
    }

    val dirPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            selectedUri = uri
            selectedPath = uri.path?.substringAfterLast(':')?.let { "/$it" } ?: uri.toString()
            saveUri(context, uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val iconBmp = remember { DroidServeIconDrawable.toBitmap(72).asImageBitmap() }
                        Image(iconBmp, contentDescription = "DroidServe", modifier = Modifier.size(32.dp))
                        Text("DroidServe", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            // Sticky primary action — always reachable without scrolling the long settings list.
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    enabled = isRunning || selectedUri != null,
                    onClick = {
                        if (isRunning) stopServer(context)
                        else { startAttempted = true; beginStart() }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                ) {
                    Icon(if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (isRunning) "Stop Server" else "Start Server", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {

            // ── Folder ──────────────────────────────────────────────────────────
            SectionCard("📂 Folder") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { dirPicker.launch(selectedUri) }, enabled = !isRunning, shape = RoundedCornerShape(10.dp)) {
                        Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text("Choose")
                    }
                    Text(
                        selectedPath.ifBlank { "No folder selected" },
                        maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                        color = if (selectedPath.isBlank()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ── Settings (port + auth) ───────────────────────────────────────────
            SectionCard("⚙️ Settings") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = {
                            portText = it.filter(Char::isDigit).take(5)
                            portError = portText.toIntOrNull()?.let { p -> p !in 1..65535 } ?: true
                            portText.toIntOrNull()?.let { p -> savePrefs { putInt(ServerForegroundService.KEY_PORT, p) } }
                        },
                        label = { Text("Port") }, singleLine = true, isError = portError, enabled = !isRunning,
                        supportingText = if (portError) {{ Text("1–65535") }} else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it; savePrefs { putString(ServerForegroundService.KEY_USERNAME, it) } },
                        label = { Text("Username") },
                        placeholder = { Text("any", color = MaterialTheme.colorScheme.outline) },
                        singleLine = true, enabled = !isRunning, modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; savePrefs { putString(ServerForegroundService.KEY_PASSWORD, it) } },
                    label = { Text("Password") },
                    placeholder = { Text("optional — leave blank for open access", color = MaterialTheme.colorScheme.outline) },
                    singleLine = true, enabled = !isRunning,
                    visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPass = !showPass }) {
                            Icon(if (showPass) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showPass) "Hide" else "Show")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ── Options ──────────────────────────────────────────────────────────
            SectionCard("🎛️ Options") {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(40); savePrefs { putString(ServerForegroundService.KEY_TITLE, title) } },
                    label = { Text("Web title") }, singleLine = true, enabled = !isRunning,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                OptionSwitch("Show hidden files", "List & serve dotfiles / __system entries", showHidden, !isRunning) {
                    showHidden = it; savePrefs { putBoolean(ServerForegroundService.KEY_SHOW_HIDDEN, it) }
                }
                OptionSwitch("Allow folder ZIP", "Expose ⬇ ZIP download buttons", allowZip, !isRunning) {
                    allowZip = it; savePrefs { putBoolean(ServerForegroundService.KEY_ALLOW_ZIP, it) }
                }
                OptionSwitch("Allow file downloads", "Off = preview/stream only", allowDownload, !isRunning) {
                    allowDownload = it; savePrefs { putBoolean(ServerForegroundService.KEY_ALLOW_DL, it) }
                }
                OptionSwitch("Keep screen awake", "Hold CPU/Wi-Fi locks while serving", keepAwake, !isRunning) {
                    keepAwake = it; savePrefs { putBoolean(ServerForegroundService.KEY_KEEP_AWAKE, it) }
                }
                OptionSwitch("Auto-start on launch", "Start automatically when the app opens", autoStart, !isRunning) {
                    autoStart = it; savePrefs { putBoolean(KEY_AUTO_START, it) }
                }
                Spacer(Modifier.height(8.dp))
                Text("Auto-stop after inactivity", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Off", 5 to "5m", 15 to "15m", 30 to "30m").forEach { (m, label) ->
                        FilterChip(
                            selected = autoStopMin == m,
                            onClick = { autoStopMin = m; savePrefs { putInt(ServerForegroundService.KEY_AUTO_STOP, m) } },
                            enabled = !isRunning,
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }
            }

            // ── Battery warning (aggressive OEM ROMs freeze the server) ──────────
            AnimatedVisibility(visible = !batteryExempt) {
                Card(
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Allow background running", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            Text("Without this the server may freeze when the app is in the background.",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { requestBatteryExemption(context, batteryLauncher) }) { Text("Fix") }
                    }
                }
            }

            // ── Status ───────────────────────────────────────────────────────────
            SectionCard("📊 Status") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(
                        if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(50)))
                    Spacer(Modifier.width(8.dp))
                    Text(if (isRunning) "Running" else "Stopped", fontWeight = FontWeight.SemiBold)
                    if (isRunning) {
                        Spacer(Modifier.weight(1f))
                        AssistChip(onClick = {}, label = { Text("$requestCount requests", fontSize = 11.sp) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.secondaryContainer))
                    }
                }
                AnimatedVisibility(visible = isRunning && url.isNotEmpty()) {
                    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        InfoRow("IP", serverIp)
                        InfoRow("Port", serverPort.toString())
                        InfoRow("URL", url, mono = true)
                        tick.let { InfoRow("Uptime", Diagnostics.formatUptime(SystemClock.elapsedRealtime() - ServerStateHolder.startedAtElapsed)) }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            FilledTonalButton(onClick = {
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText("DroidServe URL", url))
                                scope.launch { snackbar.showSnackbar("URL copied") }
                            }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) {
                                Icon(Icons.Default.ContentCopy, null, Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text("Copy", fontSize = 13.sp)
                            }
                            FilledTonalButton(onClick = {
                                try {
                                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url)
                                    }, "Share URL"))
                                } catch (_: Exception) { scope.launch { snackbar.showSnackbar("No app available to share") } }
                            }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) {
                                Icon(Icons.Default.Share, null, Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text("Share", fontSize = 13.sp)
                            }
                            FilledTonalButton(onClick = {
                                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                catch (_: Exception) { scope.launch { snackbar.showSnackbar("No browser available") } }
                            }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) {
                                Icon(Icons.Default.OpenInBrowser, null, Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text("Open", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // ── QR ─────────────────────────────────────────────────────────────
            AnimatedVisibility(visible = isRunning && qrBitmap != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Scan to connect", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(10.dp))
                        qrBitmap?.let {
                            Image(it.asImageBitmap(), contentDescription = "QR — $url", modifier = Modifier.size(180.dp))
                            Spacer(Modifier.height(10.dp))
                            TextButton(onClick = { shareQr(context, it) }) {
                                Icon(Icons.Default.Share, null, Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text("Share QR image")
                            }
                        }
                    }
                }
            }

            // ── Diagnostics (transparency) ───────────────────────────────────────
            DiagnosticsCard(context, isRunning, serverPort, requestCount, tick,
                showHidden, allowZip, allowDownload, autoStopMin, keepAwake, username, title)

            // ── Live request log ────────────────────────────────────────────────
            LiveLogCard(logEntries) { ServerLog.clear() }

            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── Diagnostics card ───────────────────────────────────────────────────────────
@Composable
private fun DiagnosticsCard(
    context: Context, isRunning: Boolean, port: Int, requestCount: Int, tick: Int,
    showHidden: Boolean, allowZip: Boolean, allowDownload: Boolean, autoStopMin: Int,
    keepAwake: Boolean, username: String, title: String
) {
    var expanded by remember { mutableStateOf(true) }
    val device  = remember { Diagnostics.device() }
    val app     = remember { Diagnostics.app(context) }
    val storage = remember(tick / 5) { Diagnostics.storage(context) }   // refresh free space every ~5s
    val ips     = remember(tick) { NetworkUtils.allIpv4() }

    SectionCard("🛰️ Diagnostics", trailing = {
        IconButton(onClick = { expanded = !expanded }) {
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "Toggle")
        }
    }) {
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                DiagGroup("Network — every reachable URL")
                if (ips.isEmpty()) KV("(no active interfaces)", "")
                ips.forEach { (iface, ip) -> KV(iface, "http://$ip:$port") }

                DiagGroup("Server")
                KV("State", if (isRunning) "running" else "stopped")
                KV("Requests", requestCount.toString())
                tick.let { KV("Uptime", Diagnostics.formatUptime(SystemClock.elapsedRealtime() - ServerStateHolder.startedAtElapsed)) }
                KV("Web title", title.ifBlank { "DroidServe" })
                KV("Auth", if (username.isBlank()) "any user" else "user: $username")
                KV("Show hidden", yn(showHidden)); KV("Folder ZIP", yn(allowZip))
                KV("Downloads", yn(allowDownload)); KV("Keep awake", yn(keepAwake))
                KV("Auto-stop", if (autoStopMin == 0) "off" else "$autoStopMin min idle")

                DiagGroup("Storage")
                storage.forEach { (k, v) -> KV(k, v) }
                DiagGroup("Device")
                device.forEach { (k, v) -> KV(k, v) }
                DiagGroup("App / Process")
                app.forEach { (k, v) -> KV(k, v) }
            }
        }
    }
}

@Composable
private fun LiveLogCard(entries: List<ServerLog.Entry>, onClear: () -> Unit) {
    var expanded by remember { mutableStateOf(true) }
    SectionCard("📡 Live request log (${entries.size})", trailing = {
        Row {
            IconButton(onClick = onClear) { Icon(Icons.Default.Delete, "Clear") }
            IconButton(onClick = { expanded = !expanded }) {
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "Toggle")
            }
        }
    }) {
        AnimatedVisibility(expanded) {
            Column {
                if (entries.isEmpty()) {
                    Text("No requests yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                } else {
                    entries.take(60).forEach { e ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(Diagnostics.clock(e.wallTime), fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                            Text(e.status.toString(), fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                color = if (e.status in 200..399) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            Text(e.method, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary)
                            Text(e.path, fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                            Text("${e.ms}ms", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                        }
                        Text(e.client, fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}

// ── Service control ──────────────────────────────────────────────────────────
private fun startServer(
    context: Context, uri: Uri, port: Int,
    password: String?, username: String?, showHidden: Boolean,
    allowZip: Boolean, allowDownload: Boolean, title: String,
    keepAwake: Boolean, autoStopMin: Int
) {
    val intent = Intent(context, ServerForegroundService::class.java).apply {
        action = ServerForegroundService.ACTION_START
        putExtra(ServerForegroundService.EXTRA_URI, uri.toString())
        putExtra(ServerForegroundService.EXTRA_PORT, port)
        putExtra(ServerForegroundService.EXTRA_PASSWORD, password)
        putExtra(ServerForegroundService.EXTRA_USERNAME, username)
        putExtra(ServerForegroundService.EXTRA_SHOW_HIDDEN, showHidden)
        putExtra(ServerForegroundService.EXTRA_ALLOW_ZIP, allowZip)
        putExtra(ServerForegroundService.EXTRA_ALLOW_DL, allowDownload)
        putExtra(ServerForegroundService.EXTRA_TITLE, title)
        putExtra(ServerForegroundService.EXTRA_KEEP_AWAKE, keepAwake)
        putExtra(ServerForegroundService.EXTRA_AUTO_STOP_MIN, autoStopMin)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
    else context.startService(intent)
}

private fun stopServer(context: Context) {
    context.startService(Intent(context, ServerForegroundService::class.java).apply {
        action = ServerForegroundService.ACTION_STOP
    })
}

// True if our foreground service is actually alive right now. Used to reconcile the UI
// state on launch/resume: the in-memory ServerStateHolder flag is lost if Android kills
// and recreates the process while the service keeps running, which previously left the
// notification showing "Running" while the reopened app showed "Start Server".
@Suppress("DEPRECATION")  // getRunningServices is deprecated but still valid for OUR OWN service
private fun isServiceRunning(context: Context): Boolean {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    return try {
        am.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == ServerForegroundService::class.java.name
        }
    } catch (_: Exception) { false }
}

// ── Battery-optimization exemption ─────────────────────────────────────────────
private fun isBatteryExempt(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

@android.annotation.SuppressLint("BatteryLife")
private fun requestBatteryExemption(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>
) {
    try {
        launcher.launch(Intent(
            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        ))
    } catch (_: Exception) {
        // Fall back to the generic battery-optimization settings list if the direct intent is blocked.
        try { context.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        catch (_: Exception) {}
    }
}

private fun shareQr(context: Context, bmp: Bitmap) {
    try {
        val file = File(context.cacheDir, "droidserve_qr.png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share QR"))
    } catch (_: Exception) {}
}

// ── UI helpers ───────────────────────────────────────────────────────────────
@Composable
private fun SectionCard(title: String, trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun OptionSwitch(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun DiagGroup(label: String) {
    Spacer(Modifier.height(8.dp))
    Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun KV(key: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(key, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(96.dp))
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun yn(b: Boolean) = if (b) "yes" else "no"

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, modifier = Modifier.width(56.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Text(value, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
    }
}

// ── QR generation (fast: setPixels in one call) ────────────────────────────────
private fun generateQrBitmap(content: String, size: Int): Bitmap? = try {
    val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        val row = y * size
        for (x in 0 until size) pixels[row + x] = if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE
    }
    Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).also { it.setPixels(pixels, 0, size, 0, 0, size, size) }
} catch (_: Exception) { null }

// ── Preference helpers ───────────────────────────────────────────────────────
private fun saveUri(context: Context, uri: Uri) =
    context.getSharedPreferences(ServerForegroundService.PREF_FILE, Context.MODE_PRIVATE)
        .edit().putString("selected_uri", uri.toString()).apply()

private fun loadSavedUri(context: Context): Uri? {
    val str = context.getSharedPreferences(ServerForegroundService.PREF_FILE, Context.MODE_PRIVATE)
        .getString("selected_uri", null) ?: return null
    val uri = Uri.parse(str)
    return if (context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }) uri else null
}

private fun displayPath(context: Context): String {
    val uri = loadSavedUri(context) ?: return ""
    return uri.path?.substringAfterLast(':')?.let { "/$it" } ?: uri.toString()
}
