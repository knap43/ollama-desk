@file:OptIn(ExperimentalFoundationApi::class)

package io.github.ollamadesk.remote.ui

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ollamadesk.remote.AppViewModel
import io.github.ollamadesk.remote.data.ChatSummary

fun relative(seconds: Double): String =
    DateUtils.getRelativeTimeSpanString((seconds * 1000).toLong(), System.currentTimeMillis(),
                                        DateUtils.MINUTE_IN_MILLIS).toString()

@Composable
fun ChatsScreen(vm: AppViewModel) {
    val chats by vm.chats.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<ChatSummary?>(null) }
    Box(Modifier.fillMaxSize()) {
        if (chats.isEmpty()) {
            Text(
                "No chats yet. Start one, and it also shows up on the computer.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
            items(chats, key = { it.id }) { chat ->
                ListItem(
                    headlineContent = { Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(if (chat.running) "Answering…" else relative(chat.updated)) },
                    leadingContent = if (chat.pinned) {
                        { Icon(Icons.Filled.PushPin, contentDescription = "Pinned", tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    trailingContent = if (chat.running) {
                        { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                    } else null,
                    modifier = Modifier.combinedClickable(
                        onClick = { vm.openChat(chat.id) },
                        onLongClick = { deleting = chat },
                    ),
                )
            }
        }
        ExtendedFloatingActionButton(
            onClick = { vm.openChat(null) },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("New chat") },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
    deleting?.let { chat ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${chat.title}”?") },
            text = { Text("It's deleted on the computer too.") },
            confirmButton = { TextButton(onClick = { deleting = null; vm.deleteChat(chat.id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
