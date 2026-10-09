@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.ollamadesk.remote.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.ollamadesk.remote.AppViewModel
import io.github.ollamadesk.remote.Connection
import io.github.ollamadesk.remote.data.Approval

@Composable
fun App(vm: AppViewModel) {
    val server by vm.server.collectAsStateWithLifecycle()
    val approvals by vm.approvals.collectAsStateWithLifecycle()
    if (server == null) {
        PairScreen(vm)
    } else {
        val open by vm.open.collectAsStateWithLifecycle()
        if (open != null) {
            BackHandler { vm.closeChat() }
            ChatScreen(vm)
        } else {
            Home(vm)
        }
    }
    approvals.firstOrNull()?.let { ApprovalDialog(it, vm) }
}

// ── pairing ──

@Composable
fun PairScreen(vm: AppViewModel) {
    val pairing by vm.pairing.collectAsStateWithLifecycle()
    val error by vm.pairError.collectAsStateWithLifecycle()
    var pasting by remember { mutableStateOf(false) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { vm.pair(it) }
    }
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            ) {
                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null,
                     tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
            }
            Text("Ollama Desk", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Use your computer's local models from this phone. On the computer, open Ollama Desk → " +
                    "Preferences → Phone access, turn it on and choose Pair a phone.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 420.dp),
            )
            Spacer(Modifier.size(8.dp))
            if (pairing) {
                CircularProgressIndicator()
                Text("Pairing…")
            } else {
                Button(modifier = Modifier.fillMaxWidth().widthIn(max = 320.dp).height(52.dp), onClick = {
                    scanner.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Scan the code shown on the computer")
                            .setBeepEnabled(false)
                            .setOrientationLocked(false)
                    )
                }) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Scan QR code")
                }
                OutlinedButton(onClick = { pasting = true },
                               modifier = Modifier.fillMaxWidth().widthIn(max = 320.dp).height(52.dp)) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Paste a pairing link")
                }
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            Text(
                "The phone and computer need to be on the same network. The connection is encrypted and only " +
                    "works with the computer you pair with.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 420.dp),
            )
        }
    }
    if (pasting) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pasting = false },
            title = { Text("Paste a pairing link") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("ollamadesk://pair?…") },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { pasting = false; vm.pair(text) }, enabled = text.isNotBlank()) { Text("Pair") }
            },
            dismissButton = { TextButton(onClick = { pasting = false }) { Text("Cancel") } },
        )
    }
}

// ── home: chats, models, tasks ──

@Composable
fun Home(vm: AppViewModel) {
    val server by vm.server.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val connectionError by vm.connectionError.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(listOf("Chats", "Models", "Scheduled tasks")[tab])
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val (color, label) = when (connection) {
                                Connection.ONLINE -> StatusColors.ok to (server?.name ?: "Connected")
                                Connection.CONNECTING -> StatusColors.warn to "Connecting…"
                                Connection.OFFLINE -> StatusColors.offline to "Offline"
                            }
                            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                            Spacer(Modifier.size(6.dp))
                            Text(label, style = MaterialTheme.typography.labelMedium,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Unpair this phone") },
                                         onClick = { menu = false; confirmUnpair = true })
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                                  icon = { Icon(Icons.AutoMirrored.Filled.Chat, null) }, label = { Text("Chats") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                                  icon = { Icon(Icons.Filled.Memory, null) }, label = { Text("Models") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                                  icon = { Icon(Icons.Filled.Schedule, null) }, label = { Text("Tasks") })
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (connection == Connection.OFFLINE && connectionError != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp)) {
                        Icon(Icons.Filled.CloudOff, null, tint = StatusColors.offline)
                        Spacer(Modifier.size(12.dp))
                        Text(connectionError ?: "", style = MaterialTheme.typography.bodyMedium,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            when (tab) {
                0 -> ChatsScreen(vm)
                1 -> ModelsScreen(vm)
                else -> TasksScreen(vm)
            }
        }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("Unpair this phone?") },
            text = { Text("To use it again, you'll need to scan a new code. You can also remove the phone on the " +
                          "computer, in Preferences → Phone access.") },
            confirmButton = { TextButton(onClick = { confirmUnpair = false; vm.unpair() }) { Text("Unpair") } },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") } },
        )
    }
}

// ── approvals ──

@Composable
fun ApprovalDialog(approval: Approval, vm: AppViewModel) {
    AlertDialog(
        onDismissRequest = { },
        icon = {
            Icon(if (approval.destructive) Icons.Filled.Warning else Icons.Filled.Shield, null,
                 tint = if (approval.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(approval.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(approval.body)
                if (approval.detail.isNotBlank()) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            approval.detail,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())
                                .padding(12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { vm.answer(approval, true) },
                colors = if (approval.destructive) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                                                contentColor = MaterialTheme.colorScheme.onError)
                } else ButtonDefaults.buttonColors(),
            ) { Text("Allow") }
        },
        dismissButton = { TextButton(onClick = { vm.answer(approval, false) }) { Text("Deny") } },
    )
}
