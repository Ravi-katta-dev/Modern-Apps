package com.vayunmathur.securemail.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.securemail.domain.mail.AddressValidator
import com.vayunmathur.securemail.domain.mail.KeyLookupPolicy
import com.vayunmathur.securemail.platform.MailUiState
import kotlinx.coroutines.launch

/**
 * Compose with per-recipient trust: encrypted by default, explicit opt-in for
 * keyless recipients, hard stop on changed/lost keys.
 */
@Composable
fun ComposeScreen(
    state: MailUiState,
    initialTo: String,
    initialSubject: String,
    initialBody: String,
    onSend: (List<String>, String, String, Boolean, String?) -> Unit,
    onBack: () -> Unit,
    onConsumeMessage: () -> Unit = {},
) {
    val messenger = rememberMessenger()
    ConsumeMessage(state.message, messenger, onConsumeMessage)
    var to by remember { mutableStateOf(initialTo) }
    var subject by remember { mutableStateOf(initialSubject) }
    var body by remember { mutableStateOf(initialBody) }
    var allowUnencrypted by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Trust resolution needs the ViewModel; the parent passes a lambda that
    // already gates. Here we only pre-validate syntax inline.
    val recipients = remember(to) { AddressValidator.parseRecipients(to) }
    val invalid = remember(recipients) { recipients.filterNot { AddressValidator.isValidAddress(it) } }
    AppScaffold(title = "Compose", scrollBehavior = appBarScrollBehavior()) { padding ->
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(
                    value = to,
                    onValueChange = { to = it },
                    label = { Text("To (comma separated)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (invalid.isNotEmpty()) {
                    Text(
                        "Check: ${invalid.joinToString()}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                OutlinedTextField(
                    value = subject,
                    onValueChange = { subject = it },
                    label = { Text("Subject") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Message") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 8,
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(checked = allowUnencrypted, onCheckedChange = { allowUnencrypted = it })
                        Text(
                            "Send unencrypted to recipients without a key. " +
                                "They will see a plain badge; you accept the downgrade.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                onSend(recipients, subject, body, allowUnencrypted, null)
                            }
                        },
                        enabled = !state.busy && recipients.isNotEmpty() && invalid.isEmpty() && body.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text(if (state.busy) "Sending…" else "Send encrypted") }
                    Button(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Cancel") }
                }
            }
        }
    }
}
