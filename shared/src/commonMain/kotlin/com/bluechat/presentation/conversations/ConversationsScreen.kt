package com.bluechat.presentation.conversations

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bluechat.domain.model.Conversation
import com.bluechat.domain.presence.Presence
import com.bluechat.presentation.components.PresenceIndicator
import com.bluechat.util.formatRelativeTime

/**
 * The home screen: conversations, not a radio scanner.
 *
 * Finding a device is now a deliberate act ("New chat") rather than the first thing the app
 * makes you do. Everything the app has ever talked to is here whether or not it is in range,
 * because a conversation is history, not a live connection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    state: ConversationsUiState,
    onConversationClicked: (Conversation) -> Unit,
    onNewChat: () -> Unit,
    onTogglePinned: (String, Boolean) -> Unit,
    onToggleMuted: (String, Boolean) -> Unit,
    onSetArchived: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onToggleShowArchived: () -> Unit,
    onContacts: () -> Unit,
    onServerMode: () -> Unit,
    onSettingsClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var deleting by remember { mutableStateOf<Conversation?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (state.showArchived) "Archived" else "Chats")
                },
                actions = {
                    if (state.showArchived || state.archivedCount > 0) {
                        TextButton(onClick = onToggleShowArchived) {
                            Text(if (state.showArchived) "Back" else "Archived (${state.archivedCount})")
                        }
                    }
                    IconButton(onClick = onContacts) {
                        Text("\uD83D\uDC64", style = MaterialTheme.typography.titleMedium)
                    }
                    IconButton(onClick = onServerMode) {
                        Text("📡", style = MaterialTheme.typography.titleMedium)
                    }
                    IconButton(onClick = onSettingsClicked) {
                        Text("⚙", style = MaterialTheme.typography.headlineSmall)
                    }
                },
            )
        },
        floatingActionButton = {
            if (!state.showArchived) {
                ExtendedFloatingActionButton(
                    onClick = onNewChat,
                    text = { Text("New chat") },
                    icon = { Text("+", style = MaterialTheme.typography.titleLarge) },
                )
            }
        },
        modifier = modifier,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.isEmpty) {
                EmptyState(showArchived = state.showArchived, onNewChat = onNewChat)
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(state.visibleConversations, key = { it.conversationId }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            presence = state.presenceOf(conversation),
                            onClick = { onConversationClicked(conversation) },
                            onTogglePinned = {
                                onTogglePinned(conversation.conversationId, !conversation.isPinned)
                            },
                            onToggleMuted = {
                                onToggleMuted(conversation.conversationId, !conversation.isMuted)
                            },
                            onSetArchived = {
                                onSetArchived(conversation.conversationId, !conversation.isArchived)
                            },
                            onDelete = { deleting = conversation },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    deleting?.let { conversation ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete chat with ${conversation.title}?") },
            text = {
                Text(
                    // Say exactly what goes and what stays. "Delete chat" is ambiguous about
                    // the contact, and the messages are unrecoverable -- there is no copy on a
                    // server to restore from.
                    //
                    // The reassurance is conditional on there actually being a contact. A
                    // conversation can outlive its contact (deleting a contact keeps the
                    // history), and promising that a contact "stays" when none exists is just
                    // a lie -- it is exactly the case where the title is a raw id.
                    when {
                        conversation.isGroup ->
                            "All messages in this chat are deleted permanently."
                        conversation.isKnownContact ->
                            "All messages are deleted permanently. " +
                                "${conversation.title} stays in your contacts."
                        else ->
                            "All messages are deleted permanently. This peer is not in your " +
                                "contacts."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(conversation.conversationId)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conversation: Conversation,
    presence: Presence,
    onClick: () -> Unit,
    onTogglePinned: () -> Unit,
    onToggleMuted: () -> Unit,
    onSetArchived: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Long-press for pin/mute/archive keeps the row itself a single tap target.
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (presence != Presence.NotNearby) {
                        PresenceIndicator(presence)
                        Spacer(Modifier.width(6.dp))
                    }
                    if (conversation.isPinned) {
                        Text("📌", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = conversation.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (conversation.hasUnread) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (conversation.isMuted) {
                        Spacer(Modifier.width(4.dp))
                        Text("🔕", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    // "You: " marks our own last message, so the list does not read as though
                    // the other person said it.
                    text = conversation.preview
                        ?.let { if (conversation.previewIsFromLocalUser) "You: $it" else it }
                        ?: "No messages yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatRelativeTime(conversation.lastMessageAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (conversation.hasUnread) {
                    Spacer(Modifier.height(4.dp))
                    UnreadBadge(conversation.unreadCount, muted = conversation.isMuted)
                }
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(if (conversation.isPinned) "Unpin" else "Pin") },
                onClick = { menuOpen = false; onTogglePinned() },
            )
            DropdownMenuItem(
                text = { Text(if (conversation.isMuted) "Unmute" else "Mute") },
                onClick = { menuOpen = false; onToggleMuted() },
            )
            DropdownMenuItem(
                text = { Text(if (conversation.isArchived) "Unarchive" else "Archive") },
                onClick = { menuOpen = false; onSetArchived() },
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = { menuOpen = false; onDelete() },
            )
        }
    }
}

@Composable
private fun UnreadBadge(count: Int, muted: Boolean) {
    Surface(
        shape = CircleShape,
        // A muted conversation still counts, but should not shout.
        color = if (muted) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.primary
        },
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = if (muted) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onPrimary
            },
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun EmptyState(showArchived: Boolean, onNewChat: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (showArchived) "Nothing archived" else "No chats yet",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (showArchived) {
                "Archived chats will appear here."
            } else {
                "Find someone nearby to start a conversation."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!showArchived) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onNewChat) { Text("New chat") }
        }
    }
}
