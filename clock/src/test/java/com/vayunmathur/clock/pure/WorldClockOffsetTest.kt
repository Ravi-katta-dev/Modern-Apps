package com.vayunmathur.clock.pure

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins world-clock offset math: one instant rendered across zones,
 * half-hour offsets, daylight-saving shifts and date-line crossings.
 *
 * Uses java.time only, with fixed instants so the results never depend
 * on the machine running the test.
 */
class WorldClockOffsetTest {
    private val summerNoonUtc = Instant.parse("2026-06-01T12:00:00Z")
    private val winterNoonUtc = Instant.parse("2026-01-15T12:00:00Z")

    private fun localIn(instant: Instant, zone: String): ZonedDateTime =
        instant.atZone(ZoneId.of(zone))

    @Test
    fun sameInstantShowsDifferentLocalTimes() {
        assertEquals(LocalTime.of(8, 0), localIn(summerNoonUtc, "America/New_York").toLocalTime())
        assertEquals(LocalTime.of(21, 0), localIn(summerNoonUtc, "Asia/Tokyo").toLocalTime())
    }

    @Test
    fun newYorkAndLondonDifferByFiveHoursInSummer() {
        val newYork = localIn(summerNoonUtc, "America/New_York")
        val london = localIn(summerNoonUtc, "Europe/London")
        val gapHours = (london.offset.totalSeconds - newYork.offset.totalSeconds) / 3600
        assertEquals(5, gapHours)
    }

    @Test
    fun kathmanduHalfHourOffsetIs5Hours45Minutes() {
        val zone = ZoneId.of("Asia/Kathmandu")
        val offsetSeconds = zone.rules.getOffset(summerNoonUtc).totalSeconds
        assertEquals((5 * 3600) + 45 * 60, offsetSeconds)
        assertEquals(LocalTime.of(17, 45), localIn(summerNoonUtc, "Asia/Kathmandu").toLocalTime())
    }

    @Test
    fun sydneyGainsOneHourInDaylightSaving() {
        val january = localIn(winterNoonUtc, "Australia/Sydney").offset.totalSeconds
        val june = localIn(summerNoonUtc, "Australia/Sydney").offset.totalSeconds
        assertEquals(3600, january - june)
    }

    @Test
    fun dateLineCitiesFallOnDifferentCalendarDates() {
        val christmas = localIn(summerNoonUtc, "Pacific/Kiritimati")
        val midway = localIn(summerNoonUtc, "Pacific/Midway")
        assertEquals(LocalDate.of(2026, 6, 2), christmas.toLocalDate())
        assertEquals(LocalDate.of(2026, 6, 1), midway.toLocalDate())
        val gapHours = (christmas.offset.totalSeconds - midway.offset.totalSeconds) / 3600
        assertEquals(25, gapHours)
    }

    @Test
    fun midnightUtcSplitsCalendarDates() {
        val night = Instant.parse("2026-06-01T00:30:00Z")
        assertEquals(LocalDate.of(2026, 6, 1), localIn(night, "Europe/Paris").toLocalDate())
        assertEquals(LocalDate.of(2026, 5, 31), localIn(night, "America/New_York").toLocalDate())
    }

    @Test
    fun springForwardSkipsHalfPastTwo() {
        val zone = ZoneId.of("America/New_York")
        val resolved = ZonedDateTime.of(LocalDate.of(2026, 3, 8), LocalTime.of(2, 30), zone)
        assertEquals(LocalTime.of(3, 30), resolved.toLocalTime())
    }

    @Test
    fun fallBackRepeatsHalfPastOneWithTwoOffsets() {
        val zone = ZoneId.of("America/New_York")
        val first = ZonedDateTime.of(LocalDate.of(2026, 11, 1), LocalTime.of(1, 30), zone)
        val second = first.withLaterOffsetAtOverlap()
        assertEquals(first.toLocalTime(), second.toLocalTime())
        assertTrue(first.offset != second.offset, "overlap must offer two offsets")
        assertEquals(3600, second.toEpochSecond() - first.toEpochSecond())
    }

    @Test
    fun invalidZoneIdThrows() {
        assertFailsWith<Exception> { ZoneId.of("Not/AZone") }
    }

    @Test
    fun blankZoneIdThrows() {
        assertFailsWith<Exception> { ZoneId.of("") }
    }

    @Test
    fun utcOffsetIsZero() {
        assertEquals(0, ZoneId.of("UTC").rules.getOffset(summerNoonUtc).totalSeconds)
    }

    @Test
    fun fixedOffsetMatchesNamedZoneInWinter() {
        val kolkata = ZoneId.of("Asia/Kolkata").rules.getOffset(winterNoonUtc).totalSeconds
        val fixed = ZoneId.of("+05:30").rules.getOffset(winterNoonUtc).totalSeconds
        assertEquals(kolkata, fixed)
    }

    @Test
    fun winterAndSummerOffsetsDifferInNewYork() {
        val summer = localIn(summerNoonUtc, "America/New_York").offset.totalSeconds
        val winter = localIn(winterNoonUtc, "America/New_York").offset.totalSeconds
        assertEquals(3600, summer - winter)
    }

    @Test
    fun tokyoHasNoDaylightSavingShift() {
        val summer = localIn(summerNoonUtc, "Asia/Tokyo").offset.totalSeconds
        val winter = localIn(winterNoonUtc, "Asia/Tokyo").offset.totalSeconds
        assertEquals(summer, winter)
    }
}
