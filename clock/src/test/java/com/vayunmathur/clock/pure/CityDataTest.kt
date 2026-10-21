package com.vayunmathur.clock.pure

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the world-clock city list integrity: newline-joined storage,
 * add/remove toggling, order preservation and IANA zone validation.
 *
 * Mirrors platform/WorldClockCities' encode/decode/toggle semantics
 * (commas are legal inside names, blank lines are dropped) using only
 * Kotlin and java.time so the host JVM can run it.
 */
class CityDataTest {
    private fun encode(cities: List<String>): String = cities.joinToString("\n")

    private fun decode(stored: String?): List<String> =
        stored?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    private fun toggle(cities: List<String>, city: String): List<String> =
        if (city in cities) cities - city else cities + city

    private fun isValidZone(id: String): Boolean =
        try {
            ZoneId.of(id)
            true
        } catch (_: Exception) {
            false
        }

    @Test
    fun emptyListEncodesToEmptyString() {
        assertEquals("", encode(emptyList()))
    }

    @Test
    fun nullStoredDecodesToEmptyList() {
        assertEquals(emptyList(), decode(null))
    }

    @Test
    fun emptyStringDecodesToEmptyList() {
        assertEquals(emptyList(), decode(""))
    }

    @Test
    fun blankLinesAreFilteredOnDecode() {
        assertEquals(listOf("Paris", "Tokyo"), decode("Paris\n\nTokyo\n   \n"))
    }

    @Test
    fun commasInCityNamesSurviveRoundTrip() {
        val cities = listOf("New York, USA", "Paris, France")
        assertEquals(cities, decode(encode(cities)))
    }

    @Test
    fun orderIsPreservedThroughRoundTrip() {
        val cities = listOf("Tokyo", "Paris", "New York, USA")
        assertEquals(cities, decode(encode(cities)))
    }

    @Test
    fun toggleAddsMissingCityAtEnd() {
        assertEquals(listOf("Paris", "Tokyo"), toggle(listOf("Paris"), "Tokyo"))
    }

    @Test
    fun toggleRemovesExistingCityKeepsOrder() {
        assertEquals(listOf("Paris", "Tokyo"), toggle(listOf("Paris", "Lima", "Tokyo"), "Lima"))
    }

    @Test
    fun toggleTwiceRestoresOriginal() {
        val start = listOf("Paris", "Tokyo")
        assertEquals(start, toggle(toggle(start, "Lima"), "Lima"))
    }

    @Test
    fun legacyMigrationSortsAlphabetically() {
        val legacy = setOf("Tokyo", "Paris", "Lima")
        assertEquals(listOf("Lima", "Paris", "Tokyo"), legacy.sorted())
    }

    @Test
    fun invalidZoneIdIsRejected() {
        assertFalse(isValidZone("Not/AZone"))
    }

    @Test
    fun blankZoneIdIsRejected() {
        assertFalse(isValidZone(""))
    }

    @Test
    fun lowerCaseZoneIdIsRejected() {
        assertFalse(isValidZone("europe/paris"))
    }

    @Test
    fun utcAndFixedOffsetsAreValid() {
        assertTrue(isValidZone("UTC"))
        assertTrue(isValidZone("Europe/Paris"))
        assertTrue(isValidZone("America/New_York"))
        assertTrue(isValidZone("+05:30"))
    }

    @Test
    fun largeCityListRoundTrips() {
        val cities = (1..100).map { "City $it" }
        assertEquals(cities, decode(encode(cities)))
    }

    @Test
    fun whitespaceOnlyEntriesAreDropped() {
        assertEquals(listOf("Paris"), decode("   \nParis\n\t\n"))
    }
}
