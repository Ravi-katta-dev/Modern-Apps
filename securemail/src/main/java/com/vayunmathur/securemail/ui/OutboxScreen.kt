package com.vayunmathur.securemail.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.securemail.data.MailRepository
import com.vayunmathur.securemail.data.OutboxMessage
import com.vayunmathur.securemail.platform.MailUiState
import com.vayunmathur.securemail.platform.MailViewModel

/** Outbox: queued sends awaiting reconnect, with manual retry. */
@Composable
fun OutboxScreen(
    state: MailUiState,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<List<OutboxMessage>>(emptyList()) }
    LaunchedEffect(Unit) {
        pending = MailRepository.get(context).outboxDao().all()
    }
    AppScaffold(title = "Outbox", scrollBehavior = appBarScrollBehavior()) { padding ->
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (pending.isEmpty()) {
                    Text("Outbox is empty.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(pending, key = { it.rowId }) { entry ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(entry.subject.ifBlank { "(no subject)" }, style = MaterialTheme.typography.titleSmall)
                                    Text(entry.toJson, style = MaterialTheme.typography.bodySmall)
                                    entry.lastError?.let {
                                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                                    }
                                    Text(
                                        "attempts: ${entry.attemptCount}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                    )
                                }
                            }
                        }
                    }
                    Button(
                        onClick = {
                            onRetry()
                            pending = emptyList()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Retry all") }
                }
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
            }
        }
    }
}
