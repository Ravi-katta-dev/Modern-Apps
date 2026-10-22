package com.vayunmathur.calendar.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vayunmathur.calendar.data.Calendar
import com.vayunmathur.calendar.data.Event
import com.vayunmathur.calendar.data.Instance
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * Month + Agenda combined view (#780, as its own layout rather than editing Month).
 * Month grid on top, agenda list below, sharing the same selected date.
 */
@Composable
fun MonthAgendaView(
    context: Context,
    events: List<Event>,
    calendars: Map<Long, Calendar>,
    calendarVisibility: Map<Long, Boolean>,
    today: LocalDate,
    dateViewing: LocalDate,
    loadInstances: suspend (Instant, Instant) -> List<Instance>,
    onEventClick: (Instance) -> Unit,
    onDayClick: (LocalDate) -> Unit,
    onDayLongClick: (LocalDate) -> Unit,
    onDateViewingChanged: (LocalDate) -> Unit,
    previewInstances: List<Instance>? = null,
) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            MonthCalendarView(
                context, events, calendars, calendarVisibility, today, dateViewing,
                loadInstances,
                onEventClick = onEventClick,
                onDayClick = onDayClick,
                onDayLongClick = onDayLongClick,
                onDateViewingChanged = onDateViewingChanged,
                previewInstances = previewInstances,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AgendaView(
                context, events, calendars, calendarVisibility, today, dateViewing,
                loadInstances,
                onEventClick = onEventClick,
                onDateViewingChanged = onDateViewingChanged,
            )
        }
    }
}
