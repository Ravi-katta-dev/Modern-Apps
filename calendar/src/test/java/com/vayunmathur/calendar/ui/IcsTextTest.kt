package com.vayunmathur.calendar.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The small pure parsers behind ICS import: TEXT unescaping, VALARM TRIGGER to
 * minutes-before, offset sniffing, TZID extraction, and DURATION maths.
 *
 * None of these touch the provider or the clock, so they run as plain JVM tests.
 */
class IcsTextTest {

    @Test
    fun `escaped newlines become real ones`() {
        assertEquals("a\nb", unescapeIcsText("a\\nb"))
        assertEquals("a\nb", unescapeIcsText("a\\Nb"))
    }

    @Test
    fun `escaped separators and backslashes collapse`() {
        assertEquals("a,b;c\\d", unescapeIcsText("a\\,b\\;c\\\\d"))
    }

    @Test
    fun `plain text and a trailing backslash pass through`() {
        assertEquals("plain", unescapeIcsText("plain"))
        assertEquals("abc\\", unescapeIcsText("abc\\"))
    }

    @Test
    fun `a start-relative trigger becomes minutes before`() {
        assertEquals(15, parseTriggerMinutesBefore("-PT15M"))
        assertEquals(90, parseTriggerMinutesBefore("-PT1H30M"))
        assertEquals(0, parseTriggerMinutesBefore("PT0S"))
        assertEquals(1560, parseTriggerMinutesBefore("-P1DT2H"))
    }

    @Test
    fun `post-start and absolute triggers have no minutes-before`() {
        assertNull(parseTriggerMinutesBefore("PT15M"))
        assertNull(parseTriggerMinutesBefore("20250704T090000Z"))
        assertNull(parseTriggerMinutesBefore(""))
        assertNull(parseTriggerMinutesBefore("tomorrow"))
    }

    @Test
    fun `only the duration half before a semicolon counts`() {
        assertEquals(15, parseTriggerMinutesBefore("-PT15M;20250704T090000Z"))
    }

    @Test
    fun `offset sniffing ignores date dashes`() {
        assertTrue(hasExplicitOffset("20250704T090000+0200"))
        assertTrue(hasExplicitOffset("20250704T090000-0500"))
        assertFalse(hasExplicitOffset("20250704T090000Z"))
        assertFalse(hasExplicitOffset("2025-07-04T09:00:00"))
        assertFalse(hasExplicitOffset("2025-07-04"))
    }

    @Test
    fun `TZID extraction is case-insensitive on the key only`() {
        assertEquals("America/New_York", extractTZID("DTSTART;TZID=America/New_York"))
        assertEquals("UTC", extractTZID("dtstart;tzid=UTC"))
        assertNull(extractTZID("DTSTART;VALUE=DATE"))
        assertNull(extractTZID("DTSTART"))
    }

    @Test
    fun `a duration shifts the start or fails cleanly`() {
        assertEquals(3_600_000L + 1_000L, tryParseDurationMillis("PT1H", 1_000L))
        assertEquals(86_400_000L, tryParseDurationMillis("P1D", 0L))
        assertNull(tryParseDurationMillis("an hour", 0L))
    }
}
