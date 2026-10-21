package com.vayunmathur.taxi.domain

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DistanceEtaTest {

    private val earthRadiusKm = 6371.0

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        require(lat1.isFinite() && lat2.isFinite() && lon1.isFinite() && lon2.isFinite()) {
            "coordinates must be finite"
        }
        require(lat1 in -90.0..90.0 && lat2 in -90.0..90.0) { "latitude out of range" }
        require(lon1 in -180.0..180.0 && lon2 in -180.0..180.0) { "longitude out of range" }
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLon = (lon2 - lon1) * PI / 180.0
        val lat1Rad = lat1 * PI / 180.0
        val lat2Rad = lat2 * PI / 180.0
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1Rad) * cos(lat2Rad) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * earthRadiusKm * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun etaMinutes(distanceKm: Double, speedKmh: Double): Double {
        require(distanceKm.isFinite() && distanceKm >= 0.0) { "distance must be non-negative" }
        require(speedKmh.isFinite() && speedKmh > 0.0) { "speed must be positive" }
        return distanceKm / speedKmh * 60.0
    }

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-6) {
        assertTrue(
            kotlin.math.abs(expected - actual) <= tolerance,
            "expected $expected but was $actual",
        )
    }

    // ---- distance ----

    @Test
    fun samePointIsZeroDistance() {
        assertClose(0.0, haversineKm(37.7749, -122.4194, 37.7749, -122.4194))
    }

    @Test
    fun oneDegreeOfLongitudeAtEquatorIsAbout111Km() {
        assertClose(111.195, haversineKm(0.0, 0.0, 0.0, 1.0), 0.5)
    }

    @Test
    fun antipodalPointsSpanHalfCircumference() {
        assertClose(20015.1, haversineKm(0.0, 0.0, 0.0, 180.0), 1.0)
    }

    @Test
    fun boundaryCoordinatesAreAccepted() {
        val distance = haversineKm(-90.0, -180.0, 90.0, 180.0)
        assertTrue(distance.isFinite() && distance > 0.0)
    }

    @Test
    fun latitudeOutsideNinetyFails() {
        assertFailsWith<IllegalArgumentException> { haversineKm(91.0, 0.0, 0.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { haversineKm(0.0, 0.0, -91.0, 0.0) }
    }

    @Test
    fun longitudeBeyond180Fails() {
        assertFailsWith<IllegalArgumentException> { haversineKm(0.0, 181.0, 0.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { haversineKm(0.0, 0.0, 0.0, -181.0) }
    }

    @Test
    fun nonFiniteCoordinateFails() {
        assertFailsWith<IllegalArgumentException> { haversineKm(Double.NaN, 0.0, 0.0, 0.0) }
        assertFailsWith<IllegalArgumentException> {
            haversineKm(0.0, 0.0, 0.0, Double.POSITIVE_INFINITY)
        }
    }

    // ---- ETA ----

    @Test
    fun zeroDistanceEtaIsZero() {
        assertClose(0.0, etaMinutes(0.0, 30.0))
    }

    @Test
    fun etaForSixtyKmAtSixtyKmhIsSixtyMinutes() {
        assertClose(60.0, etaMinutes(60.0, 60.0))
    }

    @Test
    fun etaScalesWithDistanceAndSpeed() {
        assertClose(30.0, etaMinutes(15.0, 30.0))
        assertClose(120.0, etaMinutes(10.0, 5.0))
    }

    @Test
    fun zeroSpeedFails() {
        assertFailsWith<IllegalArgumentException> { etaMinutes(5.0, 0.0) }
    }

    @Test
    fun negativeSpeedFails() {
        assertFailsWith<IllegalArgumentException> { etaMinutes(5.0, -30.0) }
    }

    @Test
    fun infiniteSpeedFails() {
        assertFailsWith<IllegalArgumentException> { etaMinutes(5.0, Double.POSITIVE_INFINITY) }
    }

    @Test
    fun negativeDistanceFails() {
        assertFailsWith<IllegalArgumentException> { etaMinutes(-1.0, 30.0) }
    }
}
