package com.vayunmathur.travel.pure

import com.vayunmathur.travel.network.PlaceDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins place-label and query validation for the airport/city
 * autocomplete: label assembly, IATA normalization, blank rejection
 * and distinct-by-code dedupe.
 *
 * PlaceDto.label is pure and called directly; query rules mirror the
 * search-box contract (short queries never hit the network).
 */
class PlaceLabelTest {
    private fun isQueryable(query: String): Boolean = query.trim().length >= 2

    private fun normalizeCode(code: String): String = code.trim().uppercase()

    private fun dedupe(places: List<PlaceDto>): List<PlaceDto> {
        val seen = LinkedHashSet<String>()
        return places.filter { seen.add(it.code.uppercase()) }
    }

    @Test
    fun fullLabelJoinsCodeNameCityCountry() {
        val p = PlaceDto("JFK", "John F Kennedy", "New York", "United States", "airport")
        assertEquals("JFK · John F Kennedy, New York, United States", p.label)
    }

    @Test
    fun nameEqualToCodeIsNotRepeated() {
        val p = PlaceDto("JFK", "JFK", "New York", "United States", "airport")
        assertEquals("JFK, New York, United States", p.label)
    }

    @Test
    fun blankPartsAreSkipped() {
        val p = PlaceDto("JFK", "", "", "", "airport")
        assertEquals("JFK", p.label)
    }

    @Test
    fun cityEqualToNameIsNotRepeated() {
        val p = PlaceDto("PAR", "Paris", "Paris", "France", "city")
        assertEquals("PAR · Paris, France", p.label)
    }

    @Test
    fun emptyPlaceLabelsToEmpty() {
        assertEquals("", PlaceDto().label)
    }

    @Test
    fun shortQueriesAreNotQueryable() {
        assertEquals(false, isQueryable(""))
        assertEquals(false, isQueryable("  "))
        assertEquals(false, isQueryable("J"))
        assertEquals(true, isQueryable("JF"))
        assertEquals(true, isQueryable("  JFK  "))
    }

    @Test
    fun unicodeLabelsAssemble() {
        val p = PlaceDto("HND", "羽田空港", "東京", "日本", "airport")
        assertEquals("HND · 羽田空港, 東京, 日本", p.label)
    }

    @Test
    fun codesNormalizeToUppercase() {
        assertEquals("JFK", normalizeCode(" jfk "))
        assertEquals("LHR", normalizeCode("lhr"))
    }

    @Test
    fun dedupeKeepsFirstOccurrence() {
        val places = listOf(
            PlaceDto("JFK", "One", "", "", "airport"),
            PlaceDto("jfk", "Two", "", "", "airport"),
            PlaceDto("LAX", "Three", "", "", "airport"),
        )
        val kept = dedupe(places)
        assertEquals(2, kept.size)
        assertEquals("One", kept[0].name)
        assertEquals("LAX", kept[1].code)
    }

    @Test
    fun labelNeverHasTrailingComma() {
        val p = PlaceDto("JFK", "John F Kennedy", "", "", "airport")
        assertTrue(!p.label.endsWith(",") && !p.label.endsWith(", "))
    }
}
