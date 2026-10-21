package com.vayunmathur.travel.pure

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins flight timezone conversion: UTC instants rendered in departure
 * and arrival zones, day-boundary crossings, DST edges and layover
 * arithmetic.
 *
 * Uses java.time only with fixed instants so results never depend on
 * the machine running the test.
 */
class FlightTimezoneTest {
    private fun localTime(instant: Instant, zone: String): String {
        val z = instant.atZone(ZoneId.of(zone))
        return "%02d:%02d".format(z.hour, z.minute)
    }

    private fun localDate(instant: Instant, zone: String): String {
        val z = instant.atZone(ZoneId.of(zone))
        return "%04d-%02d-%02d".format(z.year, z.monthValue, z.dayOfMonth)
    }

    @Test
    fun sameDepartureInstantShowsLocalTimes() {
        val dep = Instant.parse("2026-09-01T14:00:00Z")
        assertEquals("10:00", localTime(dep, "America/New_York"))
        assertEquals("15:00", localTime(dep, "Europe/London"))
        assertEquals("23:00", localTime(dep, "Asia/Tokyo"))
    }

    @Test
    fun redEyeArrivesNextCalendarDay() {
        val dep = Instant.parse("2026-09-01T22:00:00Z")
        val arr = dep.plusSeconds(7 * 3600L)
        assertEquals("2026-09-01", localDate(dep, "America/New_York"))
        assertEquals("2026-09-02", localDate(arr, "America/New_York"))
    }

    @Test
    fun dateLineEastboundLosesADay() {
        val dep = Instant.parse("2026-09-01T14:00:00Z")
        assertEquals("2026-09-01", localDate(dep, "Pacific/Honolulu"))
        assertEquals("2026-09-02", localDate(dep, "Pacific/Auckland"))
    }

    @Test
    fun dstShiftChangesOffsetAcrossSeasons() {
        val winter = Instant.parse("2026-01-15T12:00:00Z").atZone(ZoneId.of("America/New_York"))
        val summer = Instant.parse("2026-07-15T12:00:00Z").atZone(ZoneId.of("America/New_York"))
        assertEquals(3600, summer.offset.totalSeconds - winter.offset.totalSeconds)
    }

    @Test
    fun southernDstIsReversed() {
        val jan = Instant.parse("2026-01-15T12:00:00Z").atZone(ZoneId.of("Australia/Sydney"))
        val jul = Instant.parse("2026-07-15T12:00:00Z").atZone(ZoneId.of("Australia/Sydney"))
        assertTrue(jan.offset.totalSeconds > jul.offset.totalSeconds)
    }

    @Test
    fun layoverIsDifferenceOfInstants() {
        val arr1 = Instant.parse("2026-09-01T12:00:00Z")
        val dep2 = Instant.parse("2026-09-01T14:30:00Z")
        assertEquals(150L, (dep2.epochSecond - arr1.epochSecond) / 60)
    }

    @Test
    fun overnightLayoverSpansMidnightUtc() {
        val arr1 = Instant.parse("2026-09-01T23:00:00Z")
        val dep2 = Instant.parse("2026-09-02T06:00:00Z")
        assertEquals(420L, (dep2.epochSecond - arr1.epochSecond) / 60)
    }

    @Test
    fun halfHourZoneKeepsMinutes() {
        val instant = Instant.parse("2026-09-01T12:00:00Z")
        assertEquals("17:30", localTime(instant, "Asia/Kolkata"))
        assertEquals("17:45", localTime(instant, "Asia/Kathmandu"))
    }

    @Test
    fun flightDurationIsIndependentOfZones() {
        val dep = Instant.parse("2026-09-01T14:00:00Z")
        val arr = Instant.parse("2026-09-01T20:00:00Z")
        assertEquals(360L, (arr.epochSecond - dep.epochSecond) / 60)
        assertEquals("10:00", localTime(dep, "America/New_York"))
        assertEquals("21:00", localTime(arr, "Europe/London"))
    }

    @Test
    fun invalidZoneIsRejected() {
        assertTrue(runCatching { ZoneId.of("Not/AZone") }.isFailure)
    }
}
