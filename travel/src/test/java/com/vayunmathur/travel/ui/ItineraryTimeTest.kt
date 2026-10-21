package com.vayunmathur.travel.ui

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun legMinutes(departureIso: String, arrivalIso: String): Long =
    Duration.between(Instant.parse(departureIso), Instant.parse(arrivalIso)).toMinutes()

private fun totalTripMinutes(legs: List<Pair<String, String>>): Long {
    var total = 0L
    for ((index, leg) in legs.withIndex()) {
        total += legMinutes(leg.first, leg.second)
        if (index > 0) {
            total += legMinutes(legs[index - 1].second, leg.first)
        }
    }
    return total
}

private fun offsetAt(instantIso: String, zone: String): String =
    Instant.parse(instantIso).atZone(ZoneId.of(zone)).offset.toString()

private fun wallHourAt(instantIso: String, zone: String): Int =
    Instant.parse(instantIso).atZone(ZoneId.of(zone)).hour

/** Covers itinerary time math over java.time instants. */
class ItineraryTimeTest {

    @Test
    fun emptyItineraryTotalsZero() {
        assertEquals(0, totalTripMinutes(emptyList()))
    }

    @Test
    fun singleLegDuration() {
        val minutes = legMinutes("2026-10-02T03:00:00Z", "2026-10-02T12:00:00Z")
        assertEquals(540, minutes)
    }

    @Test
    fun dateLineCrossingKeepsPositiveDuration() {
        val minutes = legMinutes("2026-10-02T03:00:00Z", "2026-10-02T12:00:00Z")
        assertEquals(540, minutes)
        assertEquals(12, wallHourAt("2026-10-02T03:00:00Z", "Asia/Tokyo"))
        assertEquals(5, wallHourAt("2026-10-02T12:00:00Z", "America/Los_Angeles"))
    }

    @Test
    fun springForwardSkipsAnHour() {
        assertEquals("-05:00", offsetAt("2026-03-08T06:30:00Z", "America/New_York"))
        assertEquals("-04:00", offsetAt("2026-03-08T07:30:00Z", "America/New_York"))
        assertEquals(1, wallHourAt("2026-03-08T06:30:00Z", "America/New_York"))
        assertEquals(3, wallHourAt("2026-03-08T07:30:00Z", "America/New_York"))
        assertEquals(60, legMinutes("2026-03-08T06:30:00Z", "2026-03-08T07:30:00Z"))
    }

    @Test
    fun fallBackRepeatsAnHour() {
        assertEquals("-04:00", offsetAt("2026-11-01T05:30:00Z", "America/New_York"))
        assertEquals("-05:00", offsetAt("2026-11-01T06:30:00Z", "America/New_York"))
        assertEquals(1, wallHourAt("2026-11-01T05:30:00Z", "America/New_York"))
        assertEquals(1, wallHourAt("2026-11-01T06:30:00Z", "America/New_York"))
        assertEquals(60, legMinutes("2026-11-01T05:30:00Z", "2026-11-01T06:30:00Z"))
    }

    @Test
    fun layoverBetweenFlights() {
        assertEquals(90, legMinutes("2026-10-02T10:00:00Z", "2026-10-02T11:30:00Z"))
    }

    @Test
    fun misorderedScheduleGivesNegativeLayover() {
        val layover = legMinutes("2026-10-02T12:00:00Z", "2026-10-02T11:00:00Z")
        assertTrue(layover < 0, "expected negative layover, was $layover")
    }

    @Test
    fun overnightLayoverSpansMidnight() {
        assertEquals(420, legMinutes("2026-10-02T23:00:00Z", "2026-10-03T06:00:00Z"))
    }

    @Test
    fun multiLegTotalIncludesLayovers() {
        val legs = listOf(
            Pair("2026-10-02T03:00:00Z", "2026-10-02T04:00:00Z"),
            Pair("2026-10-02T05:30:00Z", "2026-10-02T06:30:00Z"),
        )
        assertEquals(210, totalTripMinutes(legs))
    }

    @Test
    fun zeroMinuteTurnaround() {
        assertEquals(0, legMinutes("2026-10-02T10:00:00Z", "2026-10-02T10:00:00Z"))
    }
}
