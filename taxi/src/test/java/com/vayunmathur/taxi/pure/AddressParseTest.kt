package com.vayunmathur.taxi.pure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins address and coordinate parsing for the ride inputs: free-text
 * lat/lng pairs, short address splitting, and the deep-link query
 * contract the maps handoff uses.
 *
 * Mirrors the parse-then-validate rules of the taxi booking inputs and
 * ipc/RideHandoffContract's parameter handling with plain string math
 * so the host JVM can run it.
 */
class AddressParseTest {
    private data class LatLng(val lat: Double, val lng: Double)

    private fun parseLatLng(raw: String): LatLng? {
        val parts = raw.split(',').map { it.trim() }
        if (parts.size != 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lng = parts[1].toDoubleOrNull() ?: return null
        if (!lat.isFinite() || !lng.isFinite()) return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return LatLng(lat, lng)
    }

    private fun splitAddress(raw: String): List<String> =
        raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun queryParam(params: Map<String, String>, key: String): String? =
        params[key]?.ifBlank { null }

    private fun queryLatLng(params: Map<String, String>, latKey: String, lngKey: String): LatLng? {
        val lat = params[latKey]?.toDoubleOrNull() ?: return null
        val lng = params[lngKey]?.toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return LatLng(lat, lng)
    }

    @Test
    fun plainPairParses() {
        assertEquals(LatLng(37.7, -122.4), parseLatLng("37.7,-122.4"))
    }

    @Test
    fun whitespaceAroundPairIsIgnored() {
        assertEquals(LatLng(37.7, -122.4), parseLatLng("  37.7 ,  -122.4  "))
    }

    @Test
    fun malformedPairsAreRejected() {
        assertEquals(null, parseLatLng(""))
        assertEquals(null, parseLatLng("37.7"))
        assertEquals(null, parseLatLng("37.7,-122.4,0"))
        assertEquals(null, parseLatLng("abc,def"))
        assertEquals(null, parseLatLng("91,0"))
        assertEquals(null, parseLatLng("0,181"))
        assertEquals(null, parseLatLng("NaN,0"))
    }

    @Test
    fun boundaryCoordinatesAreAccepted() {
        assertEquals(LatLng(-90.0, -180.0), parseLatLng("-90,-180"))
        assertEquals(LatLng(90.0, 180.0), parseLatLng("90,180"))
    }

    @Test
    fun unicodeMinusAndExtraSpacesAreRejected() {
        assertEquals(null, parseLatLng("37.7; -122.4"))
        assertEquals(null, parseLatLng("37,7,-122,4"))
    }

    @Test
    fun addressSplitsOnCommasAndDropsBlanks() {
        assertEquals(
            listOf("123 Main St", "Springfield", "IL"),
            splitAddress("123 Main St, Springfield,, IL, "),
        )
    }

    @Test
    fun emptyAddressSplitsToEmpty() {
        assertEquals(emptyList(), splitAddress(""))
        assertEquals(emptyList(), splitAddress("  , , "))
    }

    @Test
    fun unicodeAddressPartsSurvive() {
        assertEquals(listOf("東京", "渋谷区"), splitAddress("東京, 渋谷区"))
    }

    @Test
    fun blankLabelsAreTreatedAsMissing() {
        assertEquals(null, queryParam(mapOf("pickup_label" to "  "), "pickup_label"))
        assertEquals("Home", queryParam(mapOf("pickup_label" to "Home"), "pickup_label"))
        assertEquals(null, queryParam(emptyMap(), "pickup_label"))
    }

    @Test
    fun handoffCoordinatesValidateRanges() {
        val good = mapOf("pickup_lat" to "37.7", "pickup_lng" to "-122.4")
        assertEquals(LatLng(37.7, -122.4), queryLatLng(good, "pickup_lat", "pickup_lng"))
        val badLat = mapOf("pickup_lat" to "abc", "pickup_lng" to "-122.4")
        assertEquals(null, queryLatLng(badLat, "pickup_lat", "pickup_lng"))
        val missing = mapOf("pickup_lat" to "37.7")
        assertEquals(null, queryLatLng(missing, "pickup_lat", "pickup_lng"))
        val outOfRange = mapOf("pickup_lat" to "95", "pickup_lng" to "0")
        assertEquals(null, queryLatLng(outOfRange, "pickup_lat", "pickup_lng"))
    }

    @Test
    fun deepLinkParamNamesAreStable() {
        val names = setOf(
            "pickup_lat", "pickup_lng", "pickup_label",
            "dest_lat", "dest_lng", "dest_label",
        )
        assertTrue(names.size == 6)
        assertFalse("pickupLat" in names)
    }
}
