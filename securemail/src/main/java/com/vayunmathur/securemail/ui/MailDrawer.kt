package com.vayunmathur.securemail.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconInbox
import com.vayunmathur.library.ui.IconMail
import com.vayunmathur.library.ui.IconSend
import com.vayunmathur.library.ui.IconWarning
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalDrawerSheet
import com.vayunmathur.library.ui.NavigationDrawerItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.securemail.data.MailFolder

/**
 * Folder drawer: fixed Inbox / Outbox / Sent / Spam / Trash (no accounts —
 * SecureMail is single-identity). Selection drives `selectedFolder` in the
 * ViewModel; Outbox renders the queue, the rest filter the vault.
 */
@Composable
fun MailDrawerContent(
    selectedFolder: MailFolder,
    showingOutbox: Boolean,
    outboxCount: Int,
    address: String,
    onSelectFolder: (MailFolder) -> Unit,
    onSelectOutbox: () -> Unit,
) {
    ModalDrawerSheet {
        Column(
            modifier = Modifier.fillMaxHeight().verticalScroll(rememberScrollState()),
        ) {
            Text(
                "SecureMail",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
            )
            Text(
                address,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(horizontal = 28.dp),
            )
            Spacer(Modifier.height(12.dp))
            NavigationDrawerItem(
                label = { Text("Inbox") },
                icon = { IconInbox() },
                selected = !showingOutbox && selectedFolder == MailFolder.INBOX,
                onClick = { onSelectFolder(MailFolder.INBOX) },
            )
            NavigationDrawerItem(
                label = { Text(if (outboxCount > 0) "Outbox ($outboxCount)" else "Outbox") },
                icon = { IconMail() },
                selected = showingOutbox,
                onClick = onSelectOutbox,
            )
            NavigationDrawerItem(
                label = { Text("Sent") },
                icon = { IconSend() },
                selected = !showingOutbox && selectedFolder == MailFolder.SENT,
                onClick = { onSelectFolder(MailFolder.SENT) },
            )
            NavigationDrawerItem(
                label = { Text("Spam") },
                icon = { IconWarning() },
                selected = !showingOutbox && selectedFolder == MailFolder.SPAM,
                onClick = { onSelectFolder(MailFolder.SPAM) },
            )
            NavigationDrawerItem(
                label = { Text("Trash") },
                icon = { IconDelete() },
                selected = !showingOutbox && selectedFolder == MailFolder.TRASH,
                onClick = { onSelectFolder(MailFolder.TRASH) },
            )
        }
    }
}
