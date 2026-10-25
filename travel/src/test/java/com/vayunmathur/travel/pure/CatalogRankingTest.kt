package com.vayunmathur.travel.pure

import com.vayunmathur.travel.data.CatalogPlaceRow
import com.vayunmathur.travel.data.rankPlaces
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the offline catalog search ranking ([rankPlaces]): tier order
 * (IATA exact, metro codes, airport prefix, city prefix, name token prefix,
 * country prefix), deterministic tie-breaking, dedupe, limits and blanks.
 *
 * rankPlaces is pure (in-memory rows, no Android), so it is called directly.
 */
class CatalogRankingTest {
    private fun airport(
        code: String,
        name: String = "$code airport",
        city: String = "City $code",
        country: String = "United States",
    ) = CatalogPlaceRow(code, name, city, country, "airport")

    private fun metro(
        code: String,
        city: String = "City $code",
        country: String = "United States",
    ) = CatalogPlaceRow(code, city, city, country, "city")

    private val rows = listOf(
        airport("JFK", "John F Kennedy International Airport", "New York", "United States"),
        airport("EWR", "Newark Liberty International Airport", "New York", "United States"),
        airport("LHR", "London Heathrow Airport", "London", "United Kingdom"),
        airport("LGW", "London Gatwick Airport", "London", "United Kingdom"),
        airport("HND", "Tokyo Haneda International Airport", "Tokyo", "Japan"),
        metro("NYC", "New York", "United States"),
        metro("LON", "London", "United Kingdom"),
        // Collides with a real airport code on purpose: the airport tier wins.
        airport("BKK", "Suvarnabhumi Airport", "Bangkok", "Thailand"),
        metro("BKK", "Bangkok", "Thailand"),
        airport("GRU", "Sao Paulo Guarulhos Airport", "Sao Paulo", "Brazil"),
    )

    private fun codes(query: String, limit: Int = 8): List<String> =
        rankPlaces(query, rows, limit).map { it.code }

    @Test
    fun iataExactAirportBeatsMetro() {
        val ranked = rankPlaces("BKK", rows)
        assertEquals("airport", ranked.first().type)
        // Dedupe by code: the airport claims BKK, the metro row is dropped.
        assertEquals(listOf("BKK"), ranked.map { it.code }.filter { it == "BKK" })
        assertEquals("airport", ranked.first { it.code == "BKK" }.type)
    }

    @Test
    fun iataExactIsFirst() {
        assertEquals("JFK", codes("JFK").first())
    }

    @Test
    fun metroCodeMatchesOnPrefix() {
        assertTrue("NYC" in codes("NY"))
    }

    @Test
    fun cityPrefixFindsAirportsAndMetro() {
        val found = codes("New")
        assertTrue("NYC" in found)
        assertTrue("JFK" in found)
        assertTrue("EWR" in found)
    }

    @Test
    fun metroSortsBeforeAirportWithinLaterTiers() {
        // City-prefix tier: the NYC metro outranks its individual airports.
        val found = codes("New")
        assertTrue(found.indexOf("NYC") < found.indexOf("JFK"))
    }

    @Test
    fun nameTokenPrefixMatches() {
        assertEquals("HND", codes("Haneda").first())
    }

    @Test
    fun nameTokenPrefixIsCaseInsensitive() {
        assertEquals("LHR", codes("heathrow").first())
    }

    @Test
    fun countryPrefixMatches() {
        val found = codes("Japan")
        assertEquals(listOf("HND"), found)
    }

    @Test
    fun codePrefixTierBeatsNameTier() {
        // "LON" matches the LON metro code exactly-ish (prefix) and London by
        // city/name token; the metro code must lead.
        assertEquals("LON", codes("LON").first())
    }

    @Test
    fun shortAndBlankQueriesReturnEmpty() {
        assertEquals(emptyList(), codes(""))
        assertEquals(emptyList(), codes("  "))
        assertEquals(emptyList(), codes("J"))
    }

    @Test
    fun limitIsHonored() {
        assertEquals(2, codes("New", limit = 2).size)
    }

    @Test
    fun dedupeKeepsFirstOccurrence() {
        val dupes = listOf(
            airport("JFK", "First"),
            airport("jfk", "Second"),
        )
        val ranked = rankPlaces("JFK", dupes)
        assertEquals(1, ranked.size)
        assertEquals("First", ranked[0].name)
    }

    @Test
    fun diacriticsFoldForMatching() {
        val sao = listOf(airport("GRU", "São Paulo Guarulhos Airport", "São Paulo", "Brazil"))
        // "sao" folds to match "São" on both the name token and the city.
        assertEquals("GRU", rankPlaces("sao", sao).first().code)
        assertEquals("GRU", rankPlaces("são", sao).first().code)
    }

    @Test
    fun airportRowsKeepAirportType() {
        assertTrue(rankPlaces("JFK", rows).all { it.type == "airport" || it.code != "JFK" })
        assertEquals("city", rankPlaces("NYC", rows).first { it.code == "NYC" }.type)
    }
}
