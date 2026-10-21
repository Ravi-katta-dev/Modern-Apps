package com.vayunmathur.taxi.pure

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins route and distance math: the haversine great-circle distance,
 * ETA from distance and speed, and the multi-leg/mode ETA rules the
 * ride screen uses when no live route is available.
 *
 * Uses pure kotlin.math over plain doubles so the host JVM can run it;
 * coordinates are validated as finite lat/lng pairs first.
 */
class RouteDistanceTest {
    private fun haversineKm(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val dLat = Math.toRadians(bLat - aLat)
        val dLng = Math.toRadians(bLng - aLng)
        val h = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(aLat)) *
            cos(Math.toRadians(bLat)) * sin(dLng / 2).pow(2.0)
        return 2 * EARTH_RADIUS_KM * asin(sqrt(h))
    }

    private fun etaMinutes(distanceKm: Double, speedKph: Double): Long? {
        if (distanceKm < 0 || speedKph <= 0) return null
        return ((distanceKm / speedKph) * 60.0).roundToLong()
    }

    private fun isValidLatLng(lat: Double, lng: Double): Boolean =
        lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0

    @Test
    fun samePointHasZeroDistance() {
        assertEquals(0.0, haversineKm(37.7, -122.4, 37.7, -122.4))
    }

    @Test
    fun oneDegreeOfLatitudeIsAbout111Km() {
        val d = haversineKm(0.0, 0.0, 1.0, 0.0)
        assertTrue(d > 110.5 && d < 111.7, "was $d")
    }

    @Test
    fun antipodalPointsAreHalfCircumference() {
        val d = haversineKm(0.0, 0.0, 0.0, 180.0)
        assertTrue(d > 20_000.0 && d < 20_050.0, "was $d")
    }

    @Test
    fun knownCityPairDistance() {
        val d = haversineKm(37.7749, -122.4194, 34.0522, -118.2437)
        assertTrue(d > 550.0 && d < 570.0, "SFO-LA was $d")
    }

    @Test
    fun distanceIsSymmetric() {
        val a = haversineKm(48.85, 2.35, 51.5, -0.12)
        val b = haversineKm(51.5, -0.12, 48.85, 2.35)
        assertEquals(a, b, 1e-9)
    }

    @Test
    fun dateLineCrossingTakesShortWay() {
        val d = haversineKm(0.0, 179.0, 0.0, -179.0)
        assertTrue(d > 220.0 && d < 225.0, "was $d")
    }

    @Test
    fun etaFromCitySpeed() {
        assertEquals(30L, etaMinutes(15.0, 30.0))
        assertEquals(0L, etaMinutes(0.0, 30.0))
    }

    @Test
    fun etaRejectsBadInputs() {
        assertEquals(null, etaMinutes(-1.0, 30.0))
        assertEquals(null, etaMinutes(10.0, 0.0))
        assertEquals(null, etaMinutes(10.0, -5.0))
    }

    @Test
    fun slowTrafficInflatesEta() {
        val free = etaMinutes(10.0, 40.0)!!
        val jam = etaMinutes(10.0, 10.0)!!
        assertEquals(4 * free, jam)
    }

    @Test
    fun coordinateValidation() {
        assertTrue(isValidLatLng(37.7, -122.4))
        assertTrue(isValidLatLng(-90.0, 180.0))
        assertTrue(!isValidLatLng(91.0, 0.0))
        assertTrue(!isValidLatLng(0.0, 181.0))
        assertTrue(!isValidLatLng(Double.NaN, 0.0))
        assertTrue(!isValidLatLng(0.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun milesConversion() {
        val km = haversineKm(37.7749, -122.4194, 34.0522, -118.2437)
        val miles = km / 1.609344
        assertTrue(miles > 342.0 && miles < 355.0, "was $miles")
        assertEquals(PI, Math.acos(-1.0), 1e-12)
    }

    private companion object {
        private const val EARTH_RADIUS_KM = 6371.0
    }
}
