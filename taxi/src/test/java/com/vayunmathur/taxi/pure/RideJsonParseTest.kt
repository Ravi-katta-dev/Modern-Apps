package com.vayunmathur.taxi.pure

import com.vayunmathur.taxi.data.RideStatus
import com.vayunmathur.taxi.network.lyft.LyftRideParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Pins active-ride and driver-location JSON parsing through the
 * production parser: ride wrappers, driver/vehicle mapping, stops,
 * coordinates, ETAs and unknown statuses.
 *
 * Calls LyftRideParser directly with a lenient Json config; only the
 * JSON path is used (the binary path shares the same field mapping
 * and is pinned separately by ProtoMessageTest).
 */
class RideJsonParseTest {
    private val parser = LyftRideParser(Json { ignoreUnknownKeys = true; isLenient = true })

    private fun rideJson(status: String = "accepted"): String = """{"ride":{
      "ride_id":"r1","status":"$status",
      "driver":{"first_name":"Jo","last_name":"Smith","image_url":"u","phone_number":"p","rating":4.9},
      "vehicle":{"make":"Toyota","model":"Camry","color":"Black","license_plate":"XYZ",
      "image_url":"v"},
      "location":{"lat":37.7,"lng":-122.4,"bearing":90.0},
      "stops":[{"location":{"lat":37.7,"lng":-122.4,"place_name":"Home"},"kind":"pickup",
      "completed":false,"eta_seconds":120},
      {"location":{"lat":37.8,"lng":-122.5,"place_name":"Work"},"kind":"dropoff",
      "completed":false,"eta_seconds":600}]}}"""

    @Test
    fun fullRideMapsEveryField() {
        val ride = parser.toActiveRideJson(rideJson())!!
        assertEquals("r1", ride.rideId)
        assertEquals(RideStatus.ACCEPTED, ride.status)
        assertEquals("accepted", ride.statusRaw)
        assertEquals("Jo Smith", ride.driver?.displayName)
        assertEquals(4.9, ride.driver?.rating)
        assertEquals("Black Toyota Camry", ride.vehicle?.description)
        assertEquals("XYZ", ride.vehicle?.licensePlate)
        assertEquals(37.7, ride.driverLocation?.latitude)
        assertEquals(-122.4, ride.driverLocation?.longitude)
        assertEquals(90.0, ride.driverLocation?.bearing)
        assertEquals(2, ride.stops.size)
        assertEquals(120, ride.pickupEtaSeconds)
        assertTrue(ride.hasContent)
    }

    @Test
    fun camelCaseAliasesAreAccepted() {
        val json = """{"ride_id":"r2","status":"arrived",
          "driver":{"firstName":"A","lastName":"B","imageUrl":"u","phoneNumber":"p"},
          "vehicle":{"licensePlate":"P","imageUrl":"v"},
          "location":{"latitude":1.0,"longitude":2.0},
          "stops":[{"location":{"latitude":1.0,"longitude":2.0,"placeName":"P"},
          "kind":"pickup","etaSeconds":60,"completed":false}]}"""
        val ride = parser.toActiveRideJson(json)!!
        assertEquals("A B", ride.driver?.displayName)
        assertEquals("P", ride.vehicle?.licensePlate)
        assertEquals(60, ride.pickupEtaSeconds)
    }

    @Test
    fun unknownStatusMapsToUnknownButKeepsRaw() {
        val ride = parser.toActiveRideJson(rideJson("warping"))!!
        assertEquals(RideStatus.UNKNOWN, ride.status)
        assertEquals("warping", ride.statusRaw)
        assertTrue(ride.stops.isNotEmpty())
    }

    @Test
    fun wrapperVariantsResolveToTheRideObject() {
        for (wrapper in listOf("ride", "trip", "active_ride")) {
            val raw = """{"$wrapper":{"ride_id":"x","status":"pending"}}"""
            assertEquals("x", parser.firstRideObject(raw)?.get("ride_id").toString().trim('"'))
        }
    }

    @Test
    fun bareRootObjectIsUsedWhenNoWrapper() {
        val raw = """{"ride_id":"solo","status":"pending"}"""
        assertEquals("solo", parser.firstRideObject(raw)?.get("ride_id").toString().trim('"'))
    }

    @Test
    fun invalidJsonGivesNull() {
        assertNull(parser.firstRideObject("not json"))
        assertNull(parser.toActiveRideJson("not json"))
    }

    @Test
    fun driverLocationRequiresBothCoordinates() {
        val noLat = Json.parseToJsonElement("""{"lng":2.0}""").jsonObject
        val noLng = Json.parseToJsonElement("""{"lat":1.0}""").jsonObject
        assertNull(parser.toDriverLocationJson(noLat))
        assertNull(parser.toDriverLocationJson(noLng))
        val full = Json.parseToJsonElement("""{"lat":1.0,"lng":2.0}""").jsonObject
        assertEquals(1.0, parser.toDriverLocationJson(full)?.latitude)
        assertNull(parser.toDriverLocationJson(full)?.bearing)
    }

    @Test
    fun stopWithoutLocationStillParses() {
        val element = Json.parseToJsonElement("""{"kind":"dropoff","completed":true}""")
        val stop = parser.toStopJson(element.jsonObject)
        assertNull(stop.location)
        assertEquals(true, stop.completed)
        assertNull(stop.etaSeconds)
    }

    @Test
    fun locationV2FallbackIsUsed() {
        val element = Json.parseToJsonElement(
            """{"location_v2":{"lat":5.0,"lng":6.0,"address":"A"},"kind":"pickup"}""",
        )
        val stop = parser.toStopJson(element.jsonObject)
        assertEquals(5.0, stop.location?.latitude)
        assertEquals("A", stop.name)
        assertTrue(stop.isPickup)
    }

    @Test
    fun completedDefaultsToFalse() {
        val element = Json.parseToJsonElement("""{"kind":"pickup"}""")
        assertEquals(false, parser.toStopJson(element.jsonObject).completed)
    }
}
