package com.deekshith.droidserve

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
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
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme()
            ) {
                DroidServeApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DroidServeApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val isRunning    by ServerStateHolder.isRunning.collectAsState()
    val serverIp     by ServerStateHolder.ip.collectAsState()
    val serverPort   by ServerStateHolder.port.collectAsState()
    val requestCount by ServerStateHolder.requestCount.collectAsState()

    var selectedUri  by remember { mutableStateOf(loadSavedUri(context)) }
    var selectedPath by remember { mutableStateOf(displayPath(context)) }
    var portText     by remember { mutableStateOf("8080") }
    var portError    by remember { mutableStateOf(false) }
    var password     by remember { mutableStateOf("") }
    var showPass     by remember { mutableStateOf(false) }
    var qrBitmap     by remember { mutableStateOf<Bitmap?>(null) }

    val url = if (isRunning && serverIp.isNotEmpty()) "http://$serverIp:$serverPort" else ""

    // Error detection: if start was attempted but not running after 3s
    var startAttempted by remember { mutableStateOf(false) }
    LaunchedEffect(isRunning) {
        if (!isRunning && startAttempted) {
            startAttempted = false
            snackbar.showSnackbar("Failed to start — port may be in use")
        }
    }

    // QR code generation off main thread
    LaunchedEffect(url) {
        qrBitmap = if (url.isNotEmpty())
            withContext(Dispatchers.Default) { generateQrBitmap(url, 600) }
        else null
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
                        // Programmatic icon — no drawable resource needed
                        val iconBmp = remember {
                            DroidServeIconDrawable.toBitmap(72).asImageBitmap()
                        }
                        Image(
                            bitmap = iconBmp,
                            contentDescription = "DroidServe",
                            modifier = Modifier.size(32.dp)
                        )
                        Text("DroidServe", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {

            // ── Folder card ──────────────────────────────────────────────
            SectionCard(title = "📂 Folder") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { dirPicker.launch(selectedUri) },
                        enabled = !isRunning,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Choose")
                    }
                    Text(
                        selectedPath.ifBlank { "No folder selected" },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selectedPath.isBlank()) MaterialTheme.colorScheme.outline
                                else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ── Settings card ────────────────────────────────────────────
            SectionCard(title = "⚙️ Settings") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { v ->
                            portText = v.filter(Char::isDigit).take(5)
                            portError = portText.toIntOrNull()?.let { it !in 1..65535 } ?: true
                        },
                        label = { Text("Port") },
                        singleLine = true,
                        isError = portError,
                        enabled = !isRunning,
                        supportingText = if (portError) {{ Text("1–65535") }} else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        placeholder = { Text("optional", color = MaterialTheme.colorScheme.outline) },
                        singleLine = true,
                        enabled = !isRunning,
                        visualTransformation = if (showPass) VisualTransformation.None
                                               else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPass = !showPass }) {
                                Icon(
                                    if (showPass) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showPass) "Hide" else "Show"
                                )
                            }
                        },
                        modifier = Modifier.weight(2f)
                    )
                }
            }

            // ── Status card ──────────────────────────────────────────────
            SectionCard(title = "📊 Status") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                color = if (isRunning) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(50)
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isRunning) "Running" else "Stopped",
                        fontWeight = FontWeight.SemiBold
                    )
                    if (isRunning) {
                        Spacer(Modifier.weight(1f))
                        AssistChip(
                            onClick = {},
                            label = { Text("$requestCount requests", fontSize = 11.sp) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        )
                    }
                }

                AnimatedVisibility(visible = isRunning && url.isNotEmpty()) {
                    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        InfoRow("IP", serverIp)
                        InfoRow("Port", serverPort.toString())
                        InfoRow("URL", url, mono = true)

                        Spacer(Modifier.height(10.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            // Copy
                            FilledTonalButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("DroidServe URL", url))
                                    scope.launch { snackbar.showSnackbar("URL copied") }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, null, Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Copy", fontSize = 13.sp)
                            }
                            // Share
                            FilledTonalButton(
                                onClick = {
                                    context.startActivity(Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, url)
                                        }, "Share URL"
                                    ))
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Share, null, Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Share", fontSize = 13.sp)
                            }
                            // Open
                            FilledTonalButton(
                                onClick = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.OpenInBrowser, null, Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Open", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // ── QR code card ─────────────────────────────────────────────
            AnimatedVisibility(
                visible = isRunning && qrBitmap != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Scan to connect",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.height(10.dp))
                        qrBitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "QR — $url",
                                modifier = Modifier.size(180.dp)
                            )
                        }
                    }
                }
            }

            // ── Start / Stop ─────────────────────────────────────────────
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                onClick = {
                    scope.launch {
                        if (isRunning) {
                            stopServer(context)
                        } else {
                            val uri = selectedUri ?: run {
                                snackbar.showSnackbar("Please select a folder first"); return@launch
                            }
                            val portInt = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: run {
                                snackbar.showSnackbar("Enter a valid port (1–65535)"); return@launch
                            }
                            startAttempted = true
                            startServer(context, uri, portInt, password.ifBlank { null })
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) MaterialTheme.colorScheme.error
                                     else MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isRunning) "Stop Server" else "Start Server",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── Service control ──────────────────────────────────────────────────────────
private fun startServer(context: Context, uri: Uri, port: Int, password: String?) {
    context.startForegroundService(
        Intent(context, ServerForegroundService::class.java).apply {
            action = ServerForegroundService.ACTION_START
            putExtra(ServerForegroundService.EXTRA_URI, uri.toString())
            putExtra(ServerForegroundService.EXTRA_PORT, port)
            putExtra(ServerForegroundService.EXTRA_PASSWORD, password)
        }
    )
}

private fun stopServer(context: Context) {
    context.startService(
        Intent(context, ServerForegroundService::class.java).apply {
            action = ServerForegroundService.ACTION_STOP
        }
    )
}

// ── UI helpers ───────────────────────────────────────────────────────────────
@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            modifier = Modifier.width(44.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
        Text(
            value,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// ── QR generation ────────────────────────────────────────────────────────────
private fun generateQrBitmap(content: String, size: Int): Bitmap? = try {
    val matrix = MultiFormatWriter().encode(
        content, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.MARGIN to 1)
    )
    Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).also { bmp ->
        for (x in 0 until size) for (y in 0 until size)
            bmp.setPixel(x, y, if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
    }
} catch (_: Exception) { null }

// ── Preference helpers ───────────────────────────────────────────────────────
private fun saveUri(context: Context, uri: Uri) =
    context.getSharedPreferences("droidserve_prefs", Context.MODE_PRIVATE)
        .edit().putString("selected_uri", uri.toString()).apply()

private fun loadSavedUri(context: Context): Uri? {
    val str = context.getSharedPreferences("droidserve_prefs", Context.MODE_PRIVATE)
        .getString("selected_uri", null) ?: return null
    val uri = Uri.parse(str)
    return if (context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission })
        uri else null
}

private fun displayPath(context: Context): String {
    val uri = loadSavedUri(context) ?: return ""
    return uri.path?.substringAfterLast(':')?.let { "/$it" } ?: uri.toString()
}