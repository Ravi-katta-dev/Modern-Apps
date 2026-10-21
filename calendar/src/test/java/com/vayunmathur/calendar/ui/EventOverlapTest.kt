package com.vayunmathur.calendar.ui

import com.vayunmathur.calendar.data.Instance
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Overlap math in [computePositionedEventsForDay]: events that merely touch share no
 * column, nested and identical events do, and an overnight event is sliced at midnight.
 *
 * The zero-duration point-in-time case lives in EventPositionerTest and is not repeated.
 */
class EventOverlapTest {

    private val day = LocalDate(2026, 9, 22)
    private val zone = TimeZone.currentSystemDefault()

    private fun millis(minutes: Int): Long =
        day.atTime(minutes / 60, minutes % 60).toInstant(zone).toEpochMilliseconds()

    private fun event(id: Long, startMinutes: Int, endMinutes: Int): Instance =
        Instance(
            id = id,
            eventID = id,
            begin = millis(startMinutes),
            end = millis(endMinutes),
            timezone = zone.id,
            allDay = false,
            eventTitle = "Event $id",
            color = 0,
            rrule = null,
        )

    @Test
    fun `events that merely touch share no column`() {
        val first = event(1, 9 * 60, 10 * 60)
        val second = event(2, 10 * 60, 11 * 60)
        val positioned = computePositionedEventsForDay(listOf(first, second), day)
        assertEquals(2, positioned.size)
        positioned.forEach {
            assertEquals(1, it.totalColumns)
            assertEquals(0, it.columnIndex)
        }
    }

    @Test
    fun `nested events split into two columns`() {
        val outer = event(1, 9 * 60, 12 * 60)
        val inner = event(2, 10 * 60, 11 * 60)
        val positioned = computePositionedEventsForDay(listOf(outer, inner), day)
        assertEquals(2, positioned.size)
        assertTrue(positioned.all { it.totalColumns == 2 })
        assertEquals(setOf(0, 1), positioned.map { it.columnIndex }.toSet())
    }

    @Test
    fun `three pairwise overlaps need three columns`() {
        val events = listOf(
            event(1, 9 * 60, 10 * 60 + 30),
            event(2, 9 * 60 + 30, 11 * 60),
            event(3, 10 * 60, 10 * 60 + 30),
        )
        val positioned = computePositionedEventsForDay(events, day)
        assertEquals(3, positioned.size)
        assertTrue(positioned.all { it.totalColumns == 3 })
    }

    @Test
    fun `identical times share the width`() {
        val events = listOf(event(1, 9 * 60, 10 * 60), event(2, 9 * 60, 10 * 60))
        val positioned = computePositionedEventsForDay(events, day)
        assertEquals(2, positioned.size)
        assertTrue(positioned.all { it.totalColumns == 2 })
    }

    @Test
    fun `an overnight event is sliced at midnight`() {
        val next = LocalDate(2026, 9, 23)
        val overnight = Instance(
            id = 1,
            eventID = 1,
            begin = day.atTime(22, 0).toInstant(zone).toEpochMilliseconds(),
            end = next.atTime(2, 0).toInstant(zone).toEpochMilliseconds(),
            timezone = zone.id,
            allDay = false,
            eventTitle = "Night shift",
            color = 0,
            rrule = null,
        )
        val firstDay = computePositionedEventsForDay(listOf(overnight), day).single()
        assertEquals(22 * 60, firstDay.startMinutes)
        assertEquals(24 * 60, firstDay.endMinutes)
        val secondDay = computePositionedEventsForDay(listOf(overnight), next).single()
        assertEquals(0, secondDay.startMinutes)
        assertEquals(2 * 60, secondDay.endMinutes)
    }
}
