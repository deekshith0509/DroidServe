package com.deekshith.droidserve.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.deekshith.droidserve.tv.DiscoveredServer
import com.deekshith.droidserve.tv.UiScreen
import com.deekshith.droidserve.tv.ui.DsColor
import com.deekshith.droidserve.tv.ui.DsIcon
import com.deekshith.droidserve.tv.ui.DsIcons
import com.deekshith.droidserve.tv.ui.DsLoading
import com.deekshith.droidserve.tv.ui.DsScreen
import com.deekshith.droidserve.tv.ui.DsTextField
import com.deekshith.droidserve.tv.ui.DsType
import com.deekshith.droidserve.tv.ui.FocusRow
import com.deekshith.droidserve.tv.ui.InitialFocus
import com.deekshith.droidserve.tv.ui.NavChip
import com.deekshith.droidserve.tv.ui.PrimaryButton
import com.deekshith.droidserve.tv.ui.StateMessage

@Composable
fun CrashScreen(trace: String, onContinue: () -> Unit) {
    val continueFocus = remember { FocusRequester() }
    Column(
        Modifier.fillMaxSize().background(DsColor.Ink).padding(40.dp).verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DsIcon(DsIcons.Warning, tint = DsColor.Coral, size = 26.dp)
            Spacer(Modifier.width(12.dp))
            Text("DroidServe TV crashed last time", style = DsType.Section.copy(color = DsColor.Coral))
        }
        Spacer(Modifier.height(8.dp))
        Text("Share this report with the developer, or continue to the app.", style = DsType.Meta)
        Spacer(Modifier.height(18.dp))
        PrimaryButton("Continue", Modifier.focusRequester(continueFocus), icon = DsIcons.Chevron) { onContinue() }
        Spacer(Modifier.height(20.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DsColor.Ink2).padding(16.dp),
        ) { Text(trace, style = DsType.Mono.copy(color = DsColor.Muted)) }
    }
    InitialFocus(continueFocus, enabled = true, Unit)
}

@Composable
fun DiscoveryScreen(
    state: UiScreen.Discovery,
    onConnect: (DiscoveredServer) -> Unit,
    onManual: (String, Int) -> Unit,
    onRescan: () -> Unit,
) {
    val firstServerFocus = remember { FocusRequester() }
    DsScreen {
        Text("DroidServe", style = DsType.Hero)
        Spacer(Modifier.height(6.dp))
        Text("Pick a phone running DroidServe on your Wi-Fi", style = DsType.Body.copy(color = DsColor.Muted))
        state.error?.let { Spacer(Modifier.height(12.dp)); Text(it, style = DsType.Body.copy(color = DsColor.Coral)) }
        Spacer(Modifier.height(24.dp))

        if (state.connecting) {
            DsLoading("Connecting…")
        } else if (state.servers.isEmpty()) {
            if (state.scanning) DsLoading("Searching the network…")
            else StateMessage(
                DsIcons.Device,
                "No servers found",
                "Start DroidServe on your phone, then rescan.",
                action = {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NavChip("Rescan", DsIcons.Refresh, active = false) { onRescan() }
                        ManualConnect(onManual)
                    }
                },
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.servers, key = { "${it.host}:${it.port}" }) { srv ->
                    val mod = if (srv == state.servers.first()) Modifier.focusRequester(firstServerFocus) else Modifier
                    FocusRow(onClick = { onConnect(srv) }, modifier = mod) { focused ->
                        DsIcon(DsIcons.Device, tint = if (focused) DsColor.Amber else DsColor.Muted, size = 26.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(srv.displayName.ifEmpty { srv.name }, style = DsType.Item, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${srv.host}:${srv.port}", style = DsType.Caption)
                        }
                        DsIcon(DsIcons.Chevron, tint = DsColor.Muted, size = 20.dp)
                    }
                }
                item {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        NavChip(if (state.scanning) "Scanning…" else "Rescan", DsIcons.Refresh, active = false) { if (!state.scanning) onRescan() }
                        ManualConnect(onManual)
                    }
                }
            }
        }
    }
    InitialFocus(firstServerFocus, enabled = state.servers.isNotEmpty() && !state.connecting, state.servers.size)
}

@Composable
private fun ManualConnect(onManual: (String, Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8080") }
    val hostFocus = remember { FocusRequester() }
    Column {
        NavChip("Enter IP manually", DsIcons.Keyboard, active = open) { open = !open }
        if (open) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DsTextField(host, { host = it }, "192.168.x.x", Modifier.width(220.dp).focusRequester(hostFocus))
                Spacer(Modifier.width(10.dp)); Text(":", style = DsType.Body.copy(color = DsColor.Muted)); Spacer(Modifier.width(10.dp))
                DsTextField(port, { v -> port = v.filter { it.isDigit() } }, "8080", Modifier.width(96.dp))
                Spacer(Modifier.width(14.dp))
                PrimaryButton("Connect") { if (host.isNotBlank()) onManual(host.trim(), port.toIntOrNull() ?: 8080) }
            }
            InitialFocus(hostFocus, enabled = true, Unit)
        }
    }
}

@Composable
fun AuthScreen(
    state: UiScreen.Auth,
    onSubmit: (DiscoveredServer, String, String) -> Unit,
    onForget: (DiscoveredServer) -> Unit,
) {
    var user by remember(state) { mutableStateOf(state.savedUsername ?: "") }
    var pass by remember(state) { mutableStateOf("") }
    val userFocus = remember { FocusRequester() }
    DsScreen {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DsIcon(DsIcons.Lock, tint = DsColor.Amber, size = 28.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(state.server.displayName, style = DsType.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("This server is password protected", style = DsType.Meta)
            }
        }
        state.error?.let { Spacer(Modifier.height(12.dp)); Text(it, style = DsType.Body.copy(color = DsColor.Coral)) }
        Spacer(Modifier.height(24.dp))
        Text("Username (optional)", style = DsType.Caption)
        Spacer(Modifier.height(6.dp))
        DsTextField(user, { user = it }, "any", Modifier.width(340.dp).focusRequester(userFocus))
        Spacer(Modifier.height(16.dp))
        Text("Password", style = DsType.Caption)
        Spacer(Modifier.height(6.dp))
        DsTextField(pass, { pass = it }, "password", Modifier.width(340.dp), password = true)
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton("Connect", icon = DsIcons.Lock) { onSubmit(state.server, user, pass) }
            if (state.savedUsername != null) NavChip("Forget saved password", null, active = false) { onForget(state.server) }
        }
        Spacer(Modifier.height(16.dp))
        Text("Your password is saved for this server and reused next time.", style = DsType.Caption)
    }
    InitialFocus(userFocus, enabled = true, state)
}
