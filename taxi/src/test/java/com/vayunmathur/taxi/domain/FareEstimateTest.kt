package com.vayunmathur.taxi.domain

import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FareEstimateTest {

    private fun applySurge(fareMinor: Long, surge: Double?): Long {
        val multiplier = surge ?: 1.0
        require(multiplier.isFinite()) { "surge must be finite" }
        require(multiplier >= 1.0) { "surge must be at least 1.0" }
        return (fareMinor * multiplier).roundToLong()
    }

    private fun estimateFareMinor(
        baseMinor: Long,
        perKmMinor: Long,
        distanceKm: Double,
        perMinMinor: Long,
        durationMin: Double,
        surge: Double? = null,
        minimumMinor: Long = 0L,
    ): Long {
        require(baseMinor >= 0L) { "base fare must be non-negative" }
        require(perKmMinor >= 0L) { "per-km rate must be non-negative" }
        require(perMinMinor >= 0L) { "per-minute rate must be non-negative" }
        require(minimumMinor >= 0L) { "minimum fare must be non-negative" }
        require(distanceKm.isFinite() && distanceKm >= 0.0) { "distance must be non-negative" }
        require(durationMin.isFinite() && durationMin >= 0.0) { "duration must be non-negative" }
        val raw = baseMinor + perKmMinor * distanceKm + perMinMinor * durationMin
        return maxOf(minimumMinor, applySurge(raw.roundToLong(), surge))
    }

    // ---- base fare math ----

    @Test
    fun zeroDistanceAndTimeEqualsBase() {
        assertEquals(250L, estimateFareMinor(250L, 150L, 0.0, 30L, 0.0))
    }

    @Test
    fun zeroDistanceStillChargesTime() {
        assertEquals(550L, estimateFareMinor(250L, 150L, 0.0, 30L, 10.0))
    }

    @Test
    fun distanceAndTimeAddUp() {
        assertEquals(2000L, estimateFareMinor(200L, 150L, 10.0, 20L, 15.0))
    }

    @Test
    fun fractionalMinorAmountsRoundToNearest() {
        assertEquals(123L, estimateFareMinor(0L, 100L, 1.234, 0L, 0.0))
        assertEquals(124L, estimateFareMinor(0L, 100L, 1.236, 0L, 0.0))
    }

    @Test
    fun largeTripStaysPositive() {
        val fare = estimateFareMinor(200L, 150L, 1000.0, 20L, 1200.0)
        assertTrue(fare > 0L)
        assertEquals(200L + 150_000L + 24_000L, fare)
    }

    // ---- minimum fare ----

    @Test
    fun minimumFareEnforced() {
        assertEquals(500L, estimateFareMinor(100L, 0L, 0.0, 0L, 0.0, minimumMinor = 500L))
    }

    @Test
    fun minimumDoesNotRaiseNormalFare() {
        assertEquals(2000L, estimateFareMinor(200L, 150L, 10.0, 20L, 15.0, minimumMinor = 500L))
    }

    // ---- surge ----

    @Test
    fun surgeOfOneLeavesFareUnchanged() {
        assertEquals(2000L, estimateFareMinor(200L, 150L, 10.0, 20L, 15.0, surge = 1.0))
    }

    @Test
    fun nullSurgeLeavesFareUnchanged() {
        assertEquals(2000L, estimateFareMinor(200L, 150L, 10.0, 20L, 15.0, surge = null))
    }

    @Test
    fun surgeMultipliesFare() {
        assertEquals(1500L, estimateFareMinor(1000L, 0L, 0.0, 0L, 0.0, surge = 1.5))
        assertEquals(3000L, estimateFareMinor(1000L, 0L, 0.0, 0L, 0.0, surge = 3.0))
    }

    @Test
    fun surgeBelowOneIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            estimateFareMinor(1000L, 0L, 0.0, 0L, 0.0, surge = 0.9)
        }
    }

    @Test
    fun nonFiniteSurgeIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            estimateFareMinor(1000L, 0L, 0.0, 0L, 0.0, surge = Double.NaN)
        }
    }

    // ---- invalid input ----

    @Test
    fun negativeDistanceFails() {
        assertFailsWith<IllegalArgumentException> {
            estimateFareMinor(200L, 150L, -1.0, 20L, 15.0)
        }
    }

    @Test
    fun negativeDurationFails() {
        assertFailsWith<IllegalArgumentException> {
            estimateFareMinor(200L, 150L, 10.0, 20L, -5.0)
        }
    }

    @Test
    fun negativeBaseFails() {
        assertFailsWith<IllegalArgumentException> {
            estimateFareMinor(-1L, 150L, 10.0, 20L, 15.0)
        }
    }
}
