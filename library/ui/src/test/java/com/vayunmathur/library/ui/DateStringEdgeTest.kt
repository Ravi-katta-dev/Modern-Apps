package com.vayunmathur.library.ui

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Extends [DateStringTest] with the remaining `java.time`-only forms: [DateString.dateLong],
 * [DateString.monthDayYear], [DateString.hourLabel] in 24-hour mode and the [Instant] overloads.
 * All of these go through `java.time` locale data, so they run on the plain host JVM. The
 * ICU-backed forms (`dateWeekday`, 12-hour markers) stay on-device, as [DateStringTest] notes.
 */
class DateStringEdgeTest {

    private val pi = LocalDate(2025, 3, 14)

    @Test
    fun date_short_uk_orders_day_first() {
        // Day-first ordering is the contract; the year width (2 vs 4 digits)
        // comes from the JDK's CLDR data and varies by runtime.
        val text = DateString.dateShort(pi, Locale.UK)
        assertTrue(text.startsWith("14/03/"), "day-first ordering, got: $text")
        assertTrue(text.endsWith("25"), "year must survive, got: $text")
    }

    @Test
    fun date_short_japan_leads_with_the_year() {
        val text = DateString.dateShort(pi, Locale.JAPAN)
        assertTrue(text.startsWith("2025"), "year-first ordering, got: $text")
        assertTrue("14" in text, "day must survive, got: $text")
    }

    @Test
    fun date_short_leap_day() {
        assertEquals("2/29/24", DateString.dateShort(LocalDate(2024, 2, 29), Locale.US))
        assertEquals("12/31/25", DateString.dateShort(LocalDate(2025, 12, 31), Locale.US))
    }

    @Test
    fun date_long_spells_out_the_month() {
        assertEquals("March 14, 2025", DateString.dateLong(pi, Locale.US))
        assertEquals("14. März 2025", DateString.dateLong(pi, Locale.GERMANY))
    }

    @Test
    fun month_day_year_uses_medium_style() {
        assertEquals("Mar 14, 2025", DateString.monthDayYear(pi, Locale.US))
        assertEquals("14 Mar 2025", DateString.monthDayYear(pi, Locale.UK))
    }

    @Test
    fun time_numeric_covers_the_full_day() {
        assertEquals("23:59", DateString.timeNumeric(LocalTime(23, 59), is24Hour = true))
        assertEquals("11:59", DateString.timeNumeric(LocalTime(23, 59), is24Hour = false))
        assertEquals("12:01", DateString.timeNumeric(LocalTime(12, 1), is24Hour = false))
        assertEquals("12:01", DateString.timeNumeric(LocalTime(0, 1), is24Hour = false))
    }

    @Test
    fun time_seconds_numeric_midnight() {
        assertEquals("00:00:00", DateString.timeSecondsNumeric(LocalTime(0, 0, 0), is24Hour = true))
        assertEquals("12:00:00", DateString.timeSecondsNumeric(LocalTime(0, 0, 0), is24Hour = false))
    }

    @Test
    fun hour_label_24h_is_zero_padded() {
        assertEquals("09:00", DateString.hourLabel(9, is24Hour = true))
        assertEquals("15:00", DateString.hourLabel(15, is24Hour = true))
        assertEquals("00:00", DateString.hourLabel(0, is24Hour = true))
    }

    @Test
    fun date_time_combines_short_date_and_time() {
        val at = LocalDateTime(2025, 3, 14, 15, 5)
        assertEquals("3/14/25 15:05", DateString.dateTime(at, is24Hour = true, locale = Locale.US))
    }

    @Test
    fun instant_overload_uses_the_given_zone() {
        val at = Instant.parse("2025-03-14T15:05:00Z")
        assertEquals("3/14/25", DateString.dateShort(at, zone = TimeZone.UTC, locale = Locale.US))
        assertEquals("March 14, 2025", DateString.dateLong(at, zone = TimeZone.UTC, locale = Locale.US))
    }
}
