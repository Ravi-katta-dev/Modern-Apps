package com.vayunmathur.calendar.data

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EventRdateTest {

    private fun event(rdate: List<LocalDate>, allDay: Boolean = false) = Event(
        id = 1,
        calendarID = 1,
        title = "Test",
        description = "",
        location = "",
        color = null,
        start = LocalDateTime(LocalDate(2025, 7, 4), LocalTime(9, 0))
            .toInstant(TimeZone.of("America/New_York")).toEpochMilliseconds(),
        end = LocalDateTime(LocalDate(2025, 7, 4), LocalTime(10, 0))
            .toInstant(TimeZone.of("America/New_York")).toEpochMilliseconds(),
        timezone = "America/New_York",
        allDay = allDay,
        rrule = null,
        rdate = rdate,
    )

    @Test
    fun withoutRdateDropsOnlyThatDate() {
        val cut = event(listOf(LocalDate(2025, 7, 8), LocalDate(2025, 7, 12)))
            .withoutRdate(LocalDate(2025, 7, 8))
        assertEquals(listOf(LocalDate(2025, 7, 12)), cut.rdate)
    }

    @Test
    fun timedRdateColumnUsesUtcDateTimes() {
        // 9 AM New York in July is 13:00 UTC.
        val value = event(listOf(LocalDate(2025, 7, 8))).rdateColumnValue()
        assertEquals("20250708T130000Z", value)
    }

    @Test
    fun emptyRdateColumnIsNullSoTheColumnClears() {
        assertNull(event(emptyList()).rdateColumnValue())
    }
}
