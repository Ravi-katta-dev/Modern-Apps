package com.vayunmathur.calendar.ui.dialogs

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Calendar maths behind the recurrence dialog: day-of-year, ISO week years of 52 versus
 * 53 weeks, week-number edges around New Year, and ordinal suffixes including the teens.
 */
class RecurrenceDatesTest {

    @Test
    fun `day of year counts from one`() {
        assertEquals(1, dayOfYear(LocalDate(2026, 1, 1)))
        assertEquals(59, dayOfYear(LocalDate(2025, 2, 28)))
        assertEquals(365, dayOfYear(LocalDate(2025, 12, 31)))
    }

    @Test
    fun `leap day shifts the rest of the year`() {
        assertEquals(60, dayOfYear(LocalDate(2024, 2, 29)))
        assertEquals(61, dayOfYear(LocalDate(2024, 3, 1)))
        assertEquals(366, dayOfYear(LocalDate(2024, 12, 31)))
    }

    @Test
    fun `years starting Thursday or leap Wednesday have 53 weeks`() {
        assertEquals(53, isoWeeksInYear(2015))
        assertEquals(53, isoWeeksInYear(2020))
        assertEquals(53, isoWeeksInYear(2026))
        assertEquals(52, isoWeeksInYear(2021))
        assertEquals(52, isoWeeksInYear(2025))
    }

    @Test
    fun `week numbers wrap at both ends of the year`() {
        assertEquals(1, isoWeekNumber(LocalDate(2026, 1, 1)))
        assertEquals(1, isoWeekNumber(LocalDate(2024, 12, 30)))
        assertEquals(1, isoWeekNumber(LocalDate(2025, 12, 29)))
        assertEquals(52, isoWeekNumber(LocalDate(2025, 12, 28)))
        assertEquals(53, isoWeekNumber(LocalDate(2026, 12, 31)))
        assertEquals(29, isoWeekNumber(LocalDate(2026, 7, 15)))
    }

    @Test
    fun `ordinals handle one two three and the teens`() {
        assertEquals("1st", ordinal(1))
        assertEquals("2nd", ordinal(2))
        assertEquals("3rd", ordinal(3))
        assertEquals("4th", ordinal(4))
        assertEquals("11th", ordinal(11))
        assertEquals("12th", ordinal(12))
        assertEquals("13th", ordinal(13))
        assertEquals("21st", ordinal(21))
        assertEquals("22nd", ordinal(22))
        assertEquals("23rd", ordinal(23))
        assertEquals("111th", ordinal(111))
        assertEquals("112th", ordinal(112))
        assertEquals("113th", ordinal(113))
    }
}
