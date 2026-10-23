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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.securemail.data.StoredMessage
import com.vayunmathur.securemail.platform.MailUiState
import com.vayunmathur.securemail.service.PendingInbox
import java.text.DateFormat
import java.util.Date

/** Inbox: vault list of stored envelopes; bodies decrypt on open. */
@Composable
fun InboxScreen(
    state: MailUiState,
    onOpen: (String) -> Unit,
    onCompose: () -> Unit,
    onOutbox: () -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
    onConsumeMessage: () -> Unit,
    messages: List<StoredMessage> = emptyList(),
    outbox: Int = 0,
) {
    val messenger = rememberMessenger()
    ConsumeMessage(state.message, messenger, onConsumeMessage)
    AppScaffold(
        title = state.address.ifBlank { "SecureMail" },
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onCompose, modifier = Modifier.weight(1f)) { Text("Compose") }
                    Button(onClick = onOutbox, modifier = Modifier.weight(1f)) { Text("Outbox ($outbox)") }
                    Button(onClick = onSettings, modifier = Modifier.weight(1f)) { Text("Settings") }
                }
                if (messages.isEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "No mail yet. Share your address to receive encrypted mail.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(messages, key = { it.id }) { msg ->
                            MessageRow(msg, onOpen = { onOpen(msg.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageRow(msg: StoredMessage, onOpen: () -> Unit) {
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
        }
    }
}
