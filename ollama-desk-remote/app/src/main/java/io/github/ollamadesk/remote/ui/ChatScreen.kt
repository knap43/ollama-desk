@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.ollamadesk.remote.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.ollamadesk.remote.MicState
import kotlinx.coroutines.delay
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ollamadesk.remote.AppViewModel
import io.github.ollamadesk.remote.data.AssistantItem
import io.github.ollamadesk.remote.data.ToolItem
import io.github.ollamadesk.remote.data.UserItem

@Composable
fun ChatScreen(vm: AppViewModel) {
    val open by vm.open.collectAsStateWithLifecycle()
    val agent by vm.agent.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val voiceReady by vm.voiceReady.collectAsStateWithLifecycle()
    val mic by vm.mic.collectAsStateWithLifecycle()
    val speaking by vm.speaking.collectAsStateWithLifecycle()
    val chat = open ?: return
    var draft by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startRecording()
    }
    fun record() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED) vm.startRecording()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    // what you said lands in the message box, to check or edit before sending
    LaunchedEffect(Unit) {
        vm.transcripts.collect { text -> draft = if (draft.isBlank()) text else draft.trimEnd() + " " + text }
    }
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(mic) {
        seconds = 0
        while (mic == MicState.RECORDING) {
            delay(1000)
            seconds++
        }
    }
    val list = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    // keep the newest text in view while a reply streams in
    val lastLength = when (val last = chat.items.lastOrNull()) {
        is AssistantItem -> last.text.length + last.thinking.length
        else -> 0
    }
    LaunchedEffect(chat.items.size, lastLength, chat.running) {
        val count = list.layoutInfo.totalItemsCount
        if (count > 0) list.animateScrollToItem(count - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                navigationIcon = {
                    IconButton(onClick = { vm.closeChat() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                title = {
                    Column {
                        Text(chat.title.ifBlank { "Chat" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val shownModel = chat.model ?: model
                        if (shownModel != null) {
                            Text(shownModel, style = MaterialTheme.typography.labelMedium,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    IconToggleButton(checked = agent, onCheckedChange = { vm.setAgent(it) }) {
                        Icon(
                            Icons.Filled.SmartToy,
                            contentDescription = if (agent) "Agent mode on" else "Agent mode off",
                            tint = if (agent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    if (mic == MicState.RECORDING) {
                        Surface(
                            shape = RoundedCornerShape(26.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.weight(1f).height(56.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 18.dp)) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                                Spacer(Modifier.size(12.dp))
                                Text("Listening  %d:%02d".format(seconds / 60, seconds % 60),
                                     style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                IconButton(onClick = { vm.cancelRecording() }) { Icon(Icons.Filled.Close, "Discard") }
                            }
                        }
                    } else OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        enabled = mic == MicState.IDLE,
                        placeholder = { Text(if (mic == MicState.TRANSCRIBING) "Turning speech into text…" else "Message") },
                        maxLines = 6,
                        shape = RoundedCornerShape(26.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            unfocusedBorderColor = Color.Transparent,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            cursorColor = MaterialTheme.colorScheme.primary,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    if (chat.running) {
                        FilledIconButton(
                            onClick = { vm.stop() },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                            modifier = Modifier.padding(bottom = 4.dp).size(48.dp),
                        ) {
                            Icon(Icons.Filled.Stop, "Stop")
                        }
                    } else if (mic == MicState.RECORDING) {
                        FilledIconButton(onClick = { vm.stopRecording() }, modifier = Modifier.padding(bottom = 4.dp).size(48.dp)) {
                            Icon(Icons.Filled.Done, "Done speaking")
                        }
                    } else if (mic == MicState.TRANSCRIBING) {
                        Box(Modifier.padding(bottom = 4.dp).size(48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    } else if (draft.isBlank() && voiceReady) {
                        FilledTonalIconButton(onClick = { record() }, modifier = Modifier.padding(bottom = 4.dp).size(48.dp)) {
                            Icon(Icons.Filled.Mic, "Speak")
                        }
                    } else {
                        FilledIconButton(
                            onClick = { vm.send(draft); draft = "" },
                            enabled = draft.isNotBlank(),
                            modifier = Modifier.padding(bottom = 4.dp).size(48.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Send")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (chat.loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else if (chat.items.isEmpty()) {
                Text(
                    if (agent) "Ask anything. Agent mode is on: the model can work on the computer, and anything " +
                        "that needs your OK comes here first."
                    else "Ask anything. The model runs on your computer.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            }
            LazyColumn(
                state = list,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(chat.items) { index, item ->
                    when (item) {
                        is UserItem -> UserBubble(item)
                        is AssistantItem -> AssistantBlock(
                            item,
                            streaming = chat.running && index == chat.items.lastIndex,
                            canSpeak = voiceReady,
                            speaking = speaking == item.text.trim(),
                            onSpeak = { vm.speak(item.text.trim()) },
                        )
                        is ToolItem -> ToolCard(item)
                    }
                }
                if (chat.running && chat.items.lastOrNull() !is AssistantItem) {
                    item { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                }
            }
        }
    }
}

@Composable
private fun UserBubble(item: UserItem) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        item.scheduled?.let {
            Text("Scheduled run · ${it.replace('T', ' ')}", style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (item.files.isNotEmpty()) {
            Text("📎 " + item.files.joinToString(", "), style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (item.text.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.widthIn(max = 320.dp).padding(start = 48.dp),
            ) {
                Text(item.text, color = MaterialTheme.colorScheme.onPrimary,
                     modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun AssistantBlock(
    item: AssistantItem,
    streaming: Boolean,
    canSpeak: Boolean,
    speaking: Boolean,
    onSpeak: () -> Unit,
) {
    var showThinking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (item.thinking.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { showThinking = !showThinking },
            ) {
                Text(if (streaming && item.text.isEmpty()) "Thinking…" else "Thought process",
                     style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(if (showThinking) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null,
                     tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (showThinking) {
                Text(item.thinking.trim(), style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (item.text.isNotBlank()) {
            // while streaming, plain text avoids re-parsing markdown on every token
            if (streaming) Text(item.text, style = MaterialTheme.typography.bodyLarge) else Markdown(item.text.trim())
            if (canSpeak && !streaming) {
                IconButton(onClick = onSpeak, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (speaking) Icons.Filled.StopCircle else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (speaking) "Stop reading" else "Read aloud",
                        tint = if (speaking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else if (streaming && item.thinking.isBlank()) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun ToolCard(item: ToolItem) {
    var expanded by remember { mutableStateOf(false) }
    val (icon, tint) = when (item.status) {
        "ok" -> Icons.Filled.CheckCircle to StatusColors.ok
        "denied" -> Icons.Filled.Block to StatusColors.warn
        "pending" -> null to MaterialTheme.colorScheme.onSurfaceVariant
        else -> Icons.Filled.Warning to MaterialTheme.colorScheme.error
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().clickable(enabled = item.output.isNotBlank()) { expanded = !expanded },
    ) {
        Column(Modifier.padding(12.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.label, style = MaterialTheme.typography.titleSmall)
                    if (item.summary.isNotBlank()) {
                        Text(item.summary, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                             maxLines = if (expanded) 20 else 1, overflow = TextOverflow.Ellipsis,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.size(8.dp))
                if (icon == null) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(icon, contentDescription = item.status, tint = tint)
                }
            }
            if (expanded) {
                Text(
                    item.output,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp).heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}
