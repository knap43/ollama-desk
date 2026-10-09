package io.github.ollamadesk.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ollamadesk.remote.AppViewModel

@Composable
fun TasksScreen(vm: AppViewModel) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadTasks() }
    Box(Modifier.fillMaxSize()) {
        if (tasks.isEmpty()) {
            Text(
                "No scheduled tasks. Create them on the computer: Ollama Desk → menu → Scheduled tasks.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(tasks, key = { it.id }) { task ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(task.name, style = MaterialTheme.typography.titleMedium)
                                Text(if (task.enabled) task.schedule else "${task.schedule} · paused",
                                     style = MaterialTheme.typography.bodyMedium,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = task.enabled, onCheckedChange = { vm.setTaskEnabled(task, it) })
                        }
                        Text(task.prompt, style = MaterialTheme.typography.bodySmall, maxLines = 3,
                             overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (task.enabled && task.next.isNotBlank()) {
                            Text("Next: ${task.next}", style = MaterialTheme.typography.labelMedium)
                        }
                        task.lastRun?.let { last ->
                            val failed = task.lastStatus == "error"
                            Text(
                                "Last run ${relative(last)}${if (failed) " · failed" else ""}" +
                                    (task.lastSummary?.let { ": $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                            OutlinedButton(onClick = { vm.runTask(task) }) { Text("Run now") }
                            val chat = task.chat
                            if (chat != null) TextButton(onClick = { vm.openChat(chat) }) { Text("Open results") }
                        }
                    }
                }
            }
        }
    }
}
