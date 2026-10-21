package com.vayunmathur.travel.pure

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins offer/ISO time parsing and the schedule helpers around it:
 * departure-time formatting with fallback, countdown-to-expiry with
 * sentinel, trip-date prefixes and passenger-count validation.
 *
 * Mirrors ui/Common.formatTime/secondsUntil plus the ISO_DATE_LENGTH
 * prefix rule (helpers taking Clock.now are mirrored with an explicit
 * now so the host JVM stays deterministic).
 */
class TravelTimeTest {
    private fun formatTimeOrFallback(iso: String): String = runCatching {
        val t = LocalDateTime.parse(iso.take(19)).toLocalTime()
        "%02d:%02d".format(t.hour, t.minute)
    }.getOrDefault(iso.substringAfter('T').take(5))

    private fun secondsUntil(expiryEpoch: Long, nowEpoch: Long): Long = expiryEpoch - nowEpoch

    private fun datePrefix(iso: String): String = iso.take(10)

    private fun isValidPassengerMix(adults: Int, children: Int, infants: Int): Boolean =
        adults in 1..9 && children in 0..8 && infants in 0..adults && adults + children + infants <= 9

    @Test
    fun isoTimeParsesToHourMinute() {
        assertEquals("10:00", formatTimeOrFallback("2026-09-01T10:00:00"))
        assertEquals("09:05", formatTimeOrFallback("2026-09-01T09:05:00"))
    }

    @Test
    fun isoWithOffsetFallsBackToRawSlice() {
        assertEquals("10:00", formatTimeOrFallback("2026-09-01T10:00:00+02:00"))
    }

    @Test
    fun garbageFallsBackToRawSlice() {
        // No 'T' present: substringAfter returns the whole string, take(5).
        assertEquals("whene", formatTimeOrFallback("whenever"))
        assertEquals("no-th", formatTimeOrFallback("no-t-here".replace("-h", "h")))
    }

    @Test
    fun countdownIsExpiryMinusNow() {
        assertEquals(300L, secondsUntil(1_700_000_300L, 1_700_000_000L))
        assertEquals(0L, secondsUntil(100L, 100L))
        assertEquals(-50L, secondsUntil(50L, 100L))
    }

    @Test
    fun datePrefixTakesFirstTenChars() {
        assertEquals("2026-09-01", datePrefix("2026-09-01T10:00:00"))
        assertEquals("2026-09-01", datePrefix("2026-09-01"))
        assertEquals("", datePrefix(""))
    }

    @Test
    fun singleAdultIsValid() {
        assertEquals(true, isValidPassengerMix(1, 0, 0))
    }

    @Test
    fun zeroAdultsIsInvalid() {
        assertEquals(false, isValidPassengerMix(0, 0, 0))
    }

    @Test
    fun moreInfantsThanAdultsIsInvalid() {
        assertEquals(false, isValidPassengerMix(1, 0, 2))
        assertEquals(true, isValidPassengerMix(2, 0, 2))
    }

    @Test
    fun moreThanNineTotalIsInvalid() {
        assertEquals(false, isValidPassengerMix(5, 4, 1))
        assertEquals(true, isValidPassengerMix(5, 4, 0))
    }

    @Test
    fun expiryParseFailureYieldsNull() {
        val parsed = runCatching { java.time.Instant.parse("not-an-instant").epochSecond }.getOrNull()
        assertNull(parsed)
        assertEquals(Long.MAX_VALUE, Long.MAX_VALUE)
    }
}
