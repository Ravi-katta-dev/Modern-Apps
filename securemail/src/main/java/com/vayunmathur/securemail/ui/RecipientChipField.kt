package com.vayunmathur.securemail.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconRemoveCircle
import com.vayunmathur.library.ui.InputChip
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text

/**
 * Recipient chip field (shared by To/Cc/Bcc): type an address + Enter (Done)
 * to add it as a chip; tap a chip's ✕ to remove; `+` opens the contacts
 * picker. Invalid addresses surface [onInvalid] (messenger) instead of
 * adding. Mirrors the `MultiCategoryPicker` chip rendering
 * (`InputChip + IconRemoveCircle`) with an editable field.
 */
@Composable
fun RecipientChipField(
    label: String,
    recipients: List<String>,
    onAdd: (String) -> Boolean,
    onRemove: (String) -> Unit,
    onPickContact: () -> Unit,
    onInvalid: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    fun submit() {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (onAdd(clean)) {
            text = ""
        } else {
            onInvalid()
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            if (recipients.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    recipients.forEach { address ->
                        InputChip(
                            selected = false,
                            onClick = { onRemove(address) },
                            label = { Text(address) },
                            trailingIcon = { IconRemoveCircle(Modifier.size(18.dp)) },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Type address, Enter to add") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                IconButton(onClick = onPickContact) { IconAdd() }
            }
        }
    }
}
