package com.bluechat.presentation.contacts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bluechat.domain.model.Contact

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    state: ContactsUiState,
    onQueryChanged: (String) -> Unit,
    onRename: (contactId: String, nickname: String) -> Unit,
    onSetBlocked: (contactId: String, blocked: Boolean) -> Unit,
    onSetFavourite: (contactId: String, favourite: Boolean) -> Unit,
    onDelete: (contactId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var renaming by remember { mutableStateOf<Contact?>(null) }
    var deleting by remember { mutableStateOf<Contact?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contacts") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
        modifier = modifier,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChanged,
                label = { Text("Search") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            when {
                state.isEmpty -> Message(
                    title = "No contacts yet",
                    body = "Anyone you connect to is added here automatically.",
                )

                state.hasNoMatches -> Message(
                    title = "No matches",
                    body = "Nothing here matches \"${state.query}\".",
                )

                else -> LazyColumn(modifier = Modifier.weight(1f)) {
                    items(state.visibleContacts, key = { it.contactId }) { contact ->
                        ContactRow(
                            contact = contact,
                            onRename = { renaming = contact },
                            onToggleFavourite = {
                                onSetFavourite(contact.contactId, !contact.isFavourite)
                            },
                            onToggleBlocked = {
                                onSetBlocked(contact.contactId, !contact.isBlocked)
                            },
                            onDelete = { deleting = contact },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    renaming?.let { contact ->
        RenameDialog(
            contact = contact,
            onDismiss = { renaming = null },
            onConfirm = { nickname ->
                onRename(contact.contactId, nickname)
                renaming = null
            },
        )
    }

    deleting?.let { contact ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${contact.displayName}?") },
            // Say what is *not* deleted. Someone tidying a contact list should not have to
            // guess whether they are about to lose the conversation too.
            text = { Text("The contact is removed. Your chat history with them is kept.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(contact.contactId)
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
private fun ContactRow(
    contact: Contact,
    onRename: () -> Unit,
    onToggleFavourite: () -> Unit,
    onToggleBlocked: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = { menuOpen = true }, onLongClick = { menuOpen = true })
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = contact.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (contact.isFavourite) {
                        Spacer(Modifier.width(6.dp))
                        Text("★", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    text = when {
                        contact.isBlocked -> "Blocked"
                        // Showing the claimed name only when it differs makes a local rename
                        // visible, instead of quietly hiding what the peer calls itself.
                        contact.nickname != null && !contact.announcedName.isNullOrBlank() ->
                            "Calls itself “${contact.announcedName}”"
                        !contact.isNamed -> contact.contactId
                        else -> contact.contactId.take(Contact.SHORT_ID_LENGTH)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (contact.isBlocked) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Rename") },
                onClick = { menuOpen = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text(if (contact.isFavourite) "Remove favourite" else "Add favourite") },
                onClick = { menuOpen = false; onToggleFavourite() },
            )
            DropdownMenuItem(
                text = { Text(if (contact.isBlocked) "Unblock" else "Block") },
                onClick = { menuOpen = false; onToggleBlocked() },
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = { menuOpen = false; onDelete() },
            )
        }
    }
}

@Composable
private fun RenameDialog(
    contact: Contact,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(contact.nickname ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = contact.announcedName?.takeIf { it.isNotBlank() }
                        ?.let { "Leave empty to use “$it”, the name they chose." }
                        ?: "They have not told us a name yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Message(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
