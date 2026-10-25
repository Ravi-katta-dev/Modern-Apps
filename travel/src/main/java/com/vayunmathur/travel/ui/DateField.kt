package com.vayunmathur.travel.ui

import com.vayunmathur.library.ui.R as UiR
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import com.vayunmathur.library.ui.DateString
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.vayunmathur.library.ui.DatePicker
import com.vayunmathur.library.ui.DatePickerDialog
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconCalendar
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.rememberDatePickerState
import androidx.compose.ui.res.stringResource

/**
 * A read-only field that opens a Material date picker on tap. [value] is an ISO
 * `YYYY-MM-DD` string (empty when unset); [onDate] receives the picked ISO date.
 * [minDate]/[maxDate] optionally clamp selection (e.g. no past dates, return not
 * before depart); dates outside the range are disabled in the picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    value: String,
    onDate: (String) -> Unit,
    modifier: Modifier = Modifier,
    dateFormat: (LocalDate) -> String = DateString::dateWeekdayNoYear,
    minDate: LocalDate? = null,
    maxDate: LocalDate? = null,
) {
    var show by remember { mutableStateOf(false) }
    val display = if (value.isBlank()) "" else runCatching {
        dateFormat(LocalDate.parse(value))
    }.getOrDefault(value)

    Box(modifier) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(label) },
            leadingIcon = { IconCalendar() },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            Modifier
                .matchParentSize()
                .clickable { show = true }
        )
    }

    if (show) {
        val minMillis = minDate?.atStartOfDayIn(TimeZone.UTC)?.toEpochMilliseconds()
        val maxMillis = maxDate?.atStartOfDayIn(TimeZone.UTC)?.toEpochMilliseconds()
        val selectable = object : com.vayunmathur.library.ui.SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                (minMillis == null || utcTimeMillis >= minMillis) &&
                    (maxMillis == null || utcTimeMillis <= maxMillis)
        }
        val state = rememberDatePickerState(
            initialSelectedDateMillis = runCatching { LocalDate.parse(value) }
                .getOrNull()
                ?.atStartOfDayIn(TimeZone.UTC)
                ?.toEpochMilliseconds(),
            selectableDates = selectable,
        )
        DatePickerDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val date = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.UTC).date
                        onDate(date.toString())
                    }
                    show = false
                }) { Text(stringResource(UiR.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text(stringResource(UiR.string.cancel)) } },
        ) { DatePicker(state) }
    }
}

/** Today in UTC as a [LocalDate], for clamping pickers to no-past-dates. */
fun todayUtc(): LocalDate = Clock.System.now().toLocalDateTime(TimeZone.UTC).date
