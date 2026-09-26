package moe.reimu.nekoassistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.reimu.nekoassistant.data.ConversationEntity

@Composable
fun ConversationDrawer(
    conversations: List<ConversationEntity>, selectedId: String?, activeId: String?,
    canManage: Boolean, onDelete: (String) -> Unit,
    onSelect: (String) -> Unit,
) {
    var deleting by remember { mutableStateOf<ConversationEntity?>(null) }
    ModalDrawerSheet {
        Text("Chats", style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp))
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(conversations, key = { it.id }) { chat ->
                var menuOpen by remember { mutableStateOf(false) }
                val isSelected = chat.id == selectedId
                Surface(
                    onClick = { onSelect(chat.id) },
                    modifier = Modifier.fillMaxWidth().semantics { selected = isSelected },
                    shape = MaterialTheme.shapes.medium,
                    color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    contentColor = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                ) {
                    Row(
                        modifier = Modifier.heightIn(min = 64.dp)
                            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(chat.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (chat.id == activeId) "${chat.status} · active" else chat.status,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }, enabled = canManage) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Manage ${chat.title}")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text("Delete") }, enabled = canManage && chat.id != activeId,
                                    onClick = { menuOpen = false; deleting = chat })
                            }
                        }
                    }
                }
            }
        }
    }

    deleting?.let { chat ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete chat?") },
            text = { Text("“${chat.title}” and all its screenshots will be permanently deleted.") },
            confirmButton = {
                TextButton(enabled = canManage && chat.id != activeId,
                    onClick = { deleting = null; onDelete(chat.id) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
