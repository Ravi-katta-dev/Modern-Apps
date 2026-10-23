package com.vayunmathur.calendar.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.calendar.R
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.IconSchedule
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.R as UiR
import kotlinx.coroutines.launch

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
    val context = LocalContext.current
    var hours by remember { mutableIntStateOf(0) }
    var minutes by remember { mutableIntStateOf(15) }
    val totalMinutes = hours * 60 + minutes
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reminder_custom_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.reminder_unit_hours),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        WheelColumn(
                            range = 0..99,
                            value = hours,
                            onValue = { hours = it },
                            label = { it.toString() },
                        )
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.reminder_unit_minutes),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        WheelColumn(
                            range = 0..59,
                            value = minutes,
                            onValue = { minutes = it },
                            label = { "%02d".format(it) },
                        )
                    }
                }
                Text(
                    reminderLabel(context, totalMinutes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(totalMinutes) }, enabled = totalMinutes > 0) {
                Text(stringResource(UiR.string.add))
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) }
        },
    )
}

/**
 * A spinning-wheel number column: a [VerticalPager] with a fixed page size so a fling
 * settles on a value, with the settled row emphasized. The vertical content padding keeps
 * the first and last values centerable. Reports the settled page via [onValue]; tapping a
 * visible row animates to it.
 */
@Composable
private fun WheelColumn(
    range: IntRange,
    value: Int,
    onValue: (Int) -> Unit,
    label: (Int) -> String,
    modifier: Modifier = Modifier,
) {
    val values = remember(range) { range.toList() }
    val pagerState = rememberPagerState(
        initialPage = values.indexOf(value).coerceAtLeast(0),
    ) { values.size }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pagerState.settledPage) {
        val settled = values.getOrNull(pagerState.settledPage) ?: return@LaunchedEffect
        if (settled != value) onValue(settled)
    }
    VerticalPager(
        state = pagerState,
        pageSize = PageSize.Fixed(48.dp),
        contentPadding = PaddingValues(vertical = 48.dp),
        modifier = modifier.height(144.dp),
    ) { page ->
        val v = values[page]
        val selected = page == pagerState.currentPage
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clickable { scope.launch { pagerState.animateScrollToPage(page) } },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label(v),
                style = if (selected) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Bold else null,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
