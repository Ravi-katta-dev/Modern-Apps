package com.vayunmathur.taxi.pure

import com.vayunmathur.taxi.data.ActiveRide
import com.vayunmathur.taxi.data.DriverInfo
import com.vayunmathur.taxi.data.DriverLocation
import com.vayunmathur.taxi.data.LatLng
import com.vayunmathur.taxi.data.NewCard
import com.vayunmathur.taxi.data.Provider
import com.vayunmathur.taxi.data.RideQuote
import com.vayunmathur.taxi.data.RideStatus
import com.vayunmathur.taxi.data.RideStopInfo
import com.vayunmathur.taxi.data.VehicleInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the quote/ride data-model invariants in data/Models: range and
 * discount flags, status lifecycle sets, wire-name parsing, ETA
 * selection and display strings.
 *
 * The models are plain data with no Android dependency, so they are
 * exercised directly.
 */
class RideModelsTest {
    private fun quote(
        low: Long = 500L,
        high: Long = 700L,
        origLow: Long? = null,
        origHigh: Long? = null,
    ): RideQuote = RideQuote(
        provider = Provider.LYFT,
        productId = "lyft",
        displayName = "Lyft",
        fareLowMinor = low,
        fareHighMinor = high,
        currency = "USD",
        pickupEtaMinutes = 4,
        tripDurationMinutes = 18,
        surgeMultiplier = null,
        capacity = 4,
        originalFareLowMinor = origLow,
        originalFareHighMinor = origHigh,
    )

    @Test
    fun exactFareIsNotARange() {
        assertFalse(quote(500L, 500L).isRange)
        assertTrue(quote(500L, 700L).isRange)
    }

    @Test
    fun discountNeedsAnOriginalAmount() {
        assertFalse(quote().hasDiscount)
        assertTrue(quote(origLow = 900L).hasDiscount)
        assertTrue(quote(origHigh = 900L).hasDiscount)
        assertTrue(quote(origLow = 900L, origHigh = 1100L).hasDiscount)
    }

    @Test
    fun statusFromWireIsCaseInsensitive() {
        assertEquals(RideStatus.PENDING, RideStatus.fromWire("pending"))
        assertEquals(RideStatus.PICKED_UP, RideStatus.fromWire("PickedUp"))
        assertEquals(RideStatus.ARRIVED, RideStatus.fromWire("ARRIVED"))
    }

    @Test
    fun unknownBlankAndNullWiresMapToUnknown() {
        assertEquals(RideStatus.UNKNOWN, RideStatus.fromWire("teleported"))
        assertEquals(RideStatus.UNKNOWN, RideStatus.fromWire(""))
        assertEquals(RideStatus.UNKNOWN, RideStatus.fromWire("   "))
        assertEquals(RideStatus.UNKNOWN, RideStatus.fromWire(null))
    }

    @Test
    fun prePickupCoversDispatchThroughArrival() {
        val pre = setOf(RideStatus.PENDING, RideStatus.ACCEPTED, RideStatus.APPROACHING, RideStatus.ARRIVED)
        for (status in RideStatus.entries) assertEquals(status in pre, status.isPrePickup)
    }

    @Test
    fun terminalStatusesStopPolling() {
        val terminal = setOf(RideStatus.DROPPED_OFF, RideStatus.COMPLETED, RideStatus.PROCESSED, RideStatus.CANCELED)
        for (status in RideStatus.entries) assertEquals(status in terminal, status.isTerminal)
    }

    @Test
    fun activeMeansPrePickupOrPickedUp() {
        assertTrue(RideStatus.PENDING.isActive)
        assertTrue(RideStatus.PICKED_UP.isActive)
        assertFalse(RideStatus.COMPLETED.isActive)
        assertFalse(RideStatus.IDLE.isActive)
        assertFalse(RideStatus.UNKNOWN.isActive)
    }

    @Test
    fun pickupEtaPrefersUncompletedPickupStop() {
        val ride = ActiveRide(
            rideId = "r1", status = RideStatus.ACCEPTED, statusRaw = "accepted",
            driver = null, vehicle = null, driverLocation = null,
            stops = listOf(
                RideStopInfo(LatLng(1.0, 2.0), "Home", "pickup", 120, true),
                RideStopInfo(LatLng(3.0, 4.0), "Work", "dropoff", 600, false),
            ),
            raw = "",
        )
        assertEquals(600, ride.pickupEtaSeconds)
    }

    @Test
    fun noStopsMeansNoPickupEta() {
        val ride = ActiveRide(null, RideStatus.UNKNOWN, null, null, null, null, emptyList(), "")
        assertNull(ride.pickupEtaSeconds)
        assertFalse(ride.hasContent)
    }

    @Test
    fun contentFlagCoversIdStatusDriverAndLocation() {
        val base = ActiveRide(null, RideStatus.UNKNOWN, null, null, null, null, emptyList(), "")
        assertTrue(base.copy(rideId = "x").hasContent)
        assertTrue(base.copy(status = RideStatus.PENDING).hasContent)
        assertTrue(base.copy(driverLocation = DriverLocation(1.0, 2.0, null)).hasContent)
        assertTrue(base.copy(driver = DriverInfo("Jo", null, null, null, null)).hasContent)
    }

    @Test
    fun driverNameJoinsPresentParts() {
        assertEquals("Jo Smith", DriverInfo("Jo", "Smith", null, null, null).displayName)
        assertEquals("Jo", DriverInfo("Jo", null, null, null, null).displayName)
        assertEquals("", DriverInfo(null, null, null, null, null).displayName)
    }

    @Test
    fun vehicleDescriptionOmitsMissingParts() {
        assertEquals("Black Toyota Camry", VehicleInfo("Toyota", "Camry", "Black", null, null).description)
        assertEquals("Camry", VehicleInfo(null, "Camry", null, null, null).description)
        assertEquals("", VehicleInfo(null, null, null, null, null).description)
    }

    @Test
    fun stopKindMatchingIgnoresCase() {
        assertTrue(RideStopInfo(null, null, "Pickup", null, false).isPickup)
        assertTrue(RideStopInfo(null, null, "DROPOFF", null, false).isDropoff)
        assertFalse(RideStopInfo(null, null, "waypoint", null, false).isPickup)
        assertFalse(RideStopInfo(null, null, null, null, false).isDropoff)
    }

    @Test
    fun cardExposesOnlyLastFourAndBin() {
        val card = NewCard("4111111111111111", 12, 2030, "123", "94025")
        assertEquals("1111", card.last4)
        assertEquals("41111111", card.bin)
        assertEquals("1", NewCard("1", 1, 2030, "1", "").last4)
    }

    @Test
    fun providerLabelsAndPackages() {
        assertEquals("Uber", Provider.UBER.label)
        assertEquals("Lyft", Provider.LYFT.label)
        assertEquals("com.ubercab", Provider.UBER.packageName)
        assertEquals("me.lyft.android", Provider.LYFT.packageName)
    }
}
