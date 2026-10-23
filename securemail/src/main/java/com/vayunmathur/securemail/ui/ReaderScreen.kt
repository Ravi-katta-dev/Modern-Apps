package com.vayunmathur.securemail.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.vayunmathur.securemail.data.StoredMessage
import com.vayunmathur.securemail.platform.MailUiState
import com.vayunmathur.securemail.platform.MailViewModel

/** Reader: trust badges (verified signer / plain / locked) + decrypted body. */
@Composable
fun ReaderScreen(
    state: MailUiState,
    messageId: String,
    onReply: (StoredMessage) -> Unit,
    onBack: () -> Unit,
    message: StoredMessage? = null,
) {
    val msg = message
    AppScaffold(title = "Message", scrollBehavior = appBarScrollBehavior()) { padding ->
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (msg == null) {
                    Text("Message not found.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    TrustBadge(msg)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "From: ${msg.from.ifBlank { "(encrypted sender)" }}",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                msg.decryptedSubject ?: "(decrypting…)",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                msg.decryptedBody ?: "(decrypting…)",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Button(onClick = { onReply(msg) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Reply")
                    }
                }
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
            }
        }
    }
}

@Composable
private fun TrustBadge(msg: StoredMessage) {
    val (label, color) = when {
        msg.unencrypted -> "Unencrypted — sent or received in clear" to MaterialTheme.colorScheme.error
        msg.verifiedSigner != null -> "Signed by ${msg.verifiedSigner.take(16)}…" to MaterialTheme.colorScheme.primary
        msg.decryptedBody != null -> "Encrypted — signature not verified" to MaterialTheme.colorScheme.secondary
        else -> "Locked — decrypting…" to MaterialTheme.colorScheme.secondary
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = color, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.labelMedium)
    }
}
