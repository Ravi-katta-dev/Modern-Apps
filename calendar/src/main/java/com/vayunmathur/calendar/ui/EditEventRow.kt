package com.vayunmathur.calendar.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.calendar.R
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ProvideTextStyle

@Composable
internal fun Item(icon: @Composable () -> Unit = {}, left: @Composable () -> Unit, right: @Composable () -> Unit = {}) {
    Row(Modifier.padding(8.dp).padding(horizontal = 8.dp).height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        ProvideTextStyle(MaterialTheme.typography.bodyLarge) {
            Box(Modifier.size(24.dp)) {
                icon()
            }
            Spacer(Modifier.width(24.dp))
            Box(Modifier.weight(1f)) {
                left()
            }
            right()
        }
    }
}

/** Common reminder offsets, in minutes before the event start. */
val REMINDER_PRESETS = listOf(0, 5, 10, 15, 30, 60, 120, 1440)

/** Minute multipliers for the custom-reminder unit picker, in dropdown order. */
val REMINDER_UNIT_MULTIPLIERS = listOf(1, 60, 24 * 60, 7 * 24 * 60)

/**
 * Minutes-before for a custom reminder of [amount] [unitMinutes]-minute units, or null when the
 * amount is not positive or the total does not fit an Int.
 */
fun customReminderMinutes(amount: Long, unitMinutes: Int): Int? {
    if (amount < 1 || unitMinutes < 1) return null
    // Divide before multiplying: amount * unitMinutes can overflow Long for huge amounts.
    if (amount > Int.MAX_VALUE / unitMinutes) return null
    return (amount * unitMinutes).toInt()
}

fun reminderLabel(context: Context, minutes: Int): String = when {
    minutes <= 0 -> context.getString(R.string.reminder_at_time_of_event)
    minutes % 1440 == 0 -> context.resources.getQuantityString(R.plurals.reminder_days_before, minutes / 1440, minutes / 1440)
    minutes % 60 == 0 -> context.resources.getQuantityString(R.plurals.reminder_hours_before, minutes / 60, minutes / 60)
    else -> context.resources.getQuantityString(R.plurals.reminder_minutes_before, minutes, minutes)
}
