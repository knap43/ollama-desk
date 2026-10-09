package io.github.ollamadesk.remote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ollamadesk.remote.AppViewModel
import io.github.ollamadesk.remote.data.InstalledModel

fun bytes(n: Long): String {
    var value = n.toDouble()
    for (unit in listOf("B", "KB", "MB", "GB")) {
        if (value < 1024) return if (unit == "B") "${value.toLong()} B" else "%.1f %s".format(value, unit)
        value /= 1024
    }
    return "%.1f TB".format(value)
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
         modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp))
}

@Composable
fun ModelsScreen(vm: AppViewModel) {
    val models by vm.models.collectAsStateWithLifecycle()
    val pulls by vm.pulls.collectAsStateWithLifecycle()
    val chosen by vm.model.collectAsStateWithLifecycle()
    var download by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<InstalledModel?>(null) }
    LaunchedEffect(Unit) { vm.loadModels() }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        item { Section("For new chats") }
        item {
            ListItem(
                headlineContent = { Text("The computer's default") },
                supportingContent = { models.default?.let { Text(it) } },
                leadingContent = { RadioButton(selected = chosen == null, onClick = { vm.chooseModel(null) }) },
                modifier = Modifier.clickable { vm.chooseModel(null) },
            )
        }
        items(models.installed, key = { "installed-" + it.name }) { m ->
            ListItem(
                headlineContent = { Text(m.name) },
                supportingContent = {
                    Text(listOf(bytes(m.size), m.params, m.quant).filter { it.isNotBlank() }.joinToString(" · "))
                },
                leadingContent = { RadioButton(selected = chosen == m.name, onClick = { vm.chooseModel(m.name) }) },
                trailingContent = {
                    IconButton(onClick = { deleting = m }) { Icon(Icons.Filled.Delete, "Delete ${m.name}") }
                },
                modifier = Modifier.clickable { vm.chooseModel(m.name) },
            )
        }

        item { Section("Loaded now") }
        if (models.loaded.isEmpty()) {
            item {
                Text("No models are in memory.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                     modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        items(models.loaded, key = { "loaded-" + it.name }) { m ->
            val where = when {
                m.size > 0 && m.vram >= m.size -> "all on GPU"
                m.vram == 0L -> "all on CPU"
                else -> "${m.vram * 100 / m.size}% on GPU"
            }
            ListItem(
                headlineContent = { Text(m.name) },
                supportingContent = { Text("${bytes(m.size)} · $where") },
                trailingContent = { TextButton(onClick = { vm.unload(m.name) }) { Text("Unload") } },
            )
        }

        item { Section("Download a model") }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                OutlinedTextField(
                    value = download,
                    onValueChange = { download = it },
                    label = { Text("Name, like qwen3:8b") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.pull(download); download = "" }, enabled = download.isNotBlank()) {
                    Icon(Icons.Filled.CloudDownload, "Download")
                }
            }
        }
        items(pulls.values.toList(), key = { "pull-" + it.name }) { pull ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(pull.name, style = MaterialTheme.typography.bodyLarge)
                Text(pull.status.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                val fraction = pull.fraction
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            }
        }
    }

    deleting?.let { m ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${m.name}?") },
            text = { Text("This frees ${bytes(m.size)} on the computer. You can download it again later.") },
            confirmButton = { TextButton(onClick = { deleting = null; vm.deleteModel(m.name) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
