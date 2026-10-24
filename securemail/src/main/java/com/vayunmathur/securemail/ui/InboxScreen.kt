package com.vayunmathur.securemail.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconMenu
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.securemail.data.MailFolder
import com.vayunmathur.securemail.data.StoredMessage
import com.vayunmathur.securemail.platform.MailUiState
import java.text.DateFormat
import java.util.Date

/**
 * Folder list: renders the selected vault folder (Inbox/Sent/Spam/Trash).
 * The drawer owns navigation; this screen owns the menu button, settings
 * gear, compose FAB, and per-message trash/restore.
 */
@Composable
fun InboxScreen(
    state: MailUiState,
    folder: MailFolder,
    onOpenDrawer: () -> Unit,
    onOpen: (String) -> Unit,
    onCompose: () -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
    onConsumeMessage: () -> Unit,
    onTrash: (String) -> Unit,
    onRestore: (String) -> Unit,
    onEmptyTrash: () -> Unit,
    messages: List<StoredMessage> = emptyList(),
) {
    val messenger = rememberMessenger()
    ConsumeMessage(state.message, messenger, onConsumeMessage)
    val title = when (folder) {
        MailFolder.INBOX -> "Inbox"
        MailFolder.SENT -> "Sent"
        MailFolder.SPAM -> "Spam"
        MailFolder.TRASH -> "Trash"
    }
    AppScaffold(
        title = title,
        navigationIcon = { IconButton(onClick = onOpenDrawer) { IconMenu() } },
        actions = { IconButton(onClick = onSettings) { IconSettings() } },
        floatingActionButton = { FloatingActionButton(onClick = onCompose) { IconAdd() } },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(
                    if (state.socketConnected) "Relay: connected" else "Relay: offline — outbox holds sends",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                if (folder == MailFolder.TRASH && messages.isNotEmpty()) {
                    Button(onClick = onEmptyTrash, modifier = Modifier.fillMaxWidth()) {
                        Text("Empty trash")
                    }
                }
                if (messages.isEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            when (folder) {
                                MailFolder.INBOX -> "No mail yet. Share your address to receive encrypted mail."
                                MailFolder.SENT -> "Nothing sent yet."
                                MailFolder.SPAM -> "No spam. Verification failures land here."
                                MailFolder.TRASH -> "Trash is empty."
                            },
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(messages, key = { it.id }) { msg ->
                            MessageRow(
                                msg = msg,
                                onOpen = { onOpen(msg.id) },
                                inTrash = folder == MailFolder.TRASH,
                                onTrash = { onTrash(msg.id) },
                                onRestore = { onRestore(msg.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageRow(
    msg: StoredMessage,
    onOpen: () -> Unit,
    inTrash: Boolean,
    onTrash: () -> Unit,
    onRestore: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    msg.from.ifBlank { "(encrypted sender)" },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (msg.unencrypted) {
                    Text(
                        "plain",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (msg.decryptedBody == null) {
                    Text(
                        "locked",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                if (!msg.isRead) {
                    Text(
                        " • new",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                msg.decryptedSubject ?: "(decrypt on open)",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(msg.dateMillis)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (inTrash) {
                    Button(onClick = onRestore) { Text("Restore") }
                } else {
                    IconButton(onClick = onTrash) { IconDelete() }
                }
            }
        }
    }
}
