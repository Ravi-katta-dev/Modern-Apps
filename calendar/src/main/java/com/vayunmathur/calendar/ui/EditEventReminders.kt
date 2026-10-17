package com.vayunmathur.calendar.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.calendar.R
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.IconSchedule
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import androidx.compose.foundation.text.KeyboardOptions
import com.vayunmathur.library.ui.R as UiR

@Composable
internal fun EditEventReminders(
    reminders: List<Int>,
    onReminders: (List<Int>) -> Unit,
) {
    val context = LocalContext.current
    // Reminders
    reminders.forEach { minutes ->
        Item(
            { IconSchedule() },
            { Text(reminderLabel(context, minutes)) },
            { Text(stringResource(UiR.string.remove), Modifier.clickable { onReminders(reminders - minutes) }) },
        )
    }
    var addReminderExpanded by remember { mutableStateOf(false) }
    var showCustomDialog by remember { mutableStateOf(false) }
    val available = REMINDER_PRESETS.filter { it !in reminders }
    // The Custom option means the add row always has something to offer, even when
    // every preset is already selected.
    Item(
        { IconSchedule() },
        {
            Box {
                Text(stringResource(R.string.add_reminder), Modifier.clickable { addReminderExpanded = true })
                DropdownMenu(addReminderExpanded, { addReminderExpanded = false }) {
                    available.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(reminderLabel(context, m)) },
                            onClick = {
                                addReminderExpanded = false
                                onReminders((reminders + m).sorted())
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reminder_custom)) },
                        onClick = {
                            addReminderExpanded = false
                            showCustomDialog = true
                        },
                    )
                }
            }
        },
    )
    if (showCustomDialog) {
        CustomReminderDialog(
            onDismiss = { showCustomDialog = false },
            onConfirm = { minutes ->
                showCustomDialog = false
                if (minutes !in reminders) onReminders((reminders + minutes).sorted())
            },
        )
    }
}

@Composable
private fun CustomReminderDialog(
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var amountStr by remember { mutableStateOf("") }
    var unitIndex by remember { mutableIntStateOf(1) }
    val units = listOf(
        stringResource(R.string.reminder_unit_minutes),
        stringResource(R.string.reminder_unit_hours),
        stringResource(R.string.reminder_unit_days),
        stringResource(R.string.reminder_unit_weeks),
    )
    val minutes = amountStr.toLongOrNull()
        ?.let { customReminderMinutes(it, REMINDER_UNIT_MULTIPLIERS[unitIndex]) }
    var unitExpanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reminder_custom_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = amountStr,
                    onValueChange = { amountStr = it.filter(Char::isDigit).take(9) },
                    label = { Text(stringResource(R.string.reminder_custom_amount)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    OutlinedTextField(
                        value = units[unitIndex],
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().clickable { unitExpanded = true },
                        label = { Text(stringResource(R.string.reminder_custom_unit)) },
                    )
                    DropdownMenu(unitExpanded, { unitExpanded = false }) {
                        units.forEachIndexed { i, label ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    unitIndex = i
                                    unitExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { minutes?.let(onConfirm) }, enabled = minutes != null) {
                Text(stringResource(UiR.string.add))
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) }
        },
    )
}
