package com.vayunmathur.taxi.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AddressParseTest {

    private fun parseAddress(raw: String): Map<String, String?> {
        require(raw.isNotBlank()) { "address must not be blank" }
        val parts = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        require(parts.isNotEmpty()) { "address must not be blank" }
        val street = parts[0]
        var city: String? = null
        var region: String? = null
        var postal: String? = null
        if (parts.size >= 2) {
            city = parts[1]
        }
        if (parts.size >= 3) {
            val tail = parts.subList(2, parts.size).joinToString(", ")
            val tokens = tail.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val last = tokens.lastOrNull()
            if (last != null && last.all { it.isDigit() } && last.length in 3..10) {
                postal = last
                region = tokens.dropLast(1).joinToString(" ").ifBlank { null }
            } else {
                region = tail.ifBlank { null }
            }
        }
        return mapOf("street" to street, "city" to city, "region" to region, "postal" to postal)
    }

    @Test
    fun fullAddressParsesAllParts() {
        val parsed = parseAddress("123 Main St, Springfield, IL 62701")
        assertEquals("123 Main St", parsed["street"])
        assertEquals("Springfield", parsed["city"])
        assertEquals("IL", parsed["region"])
        assertEquals("62701", parsed["postal"])
    }

    @Test
    fun addressWithoutPostalLeavesPostalNull() {
        val parsed = parseAddress("456 Oak Ave, Riverton, CA")
        assertEquals("456 Oak Ave", parsed["street"])
        assertEquals("Riverton", parsed["city"])
        assertEquals("CA", parsed["region"])
        assertNull(parsed["postal"])
    }

    @Test
    fun zipOnlyTailLeavesRegionNull() {
        val parsed = parseAddress("789 Pine Rd, Lakeside, 90210")
        assertEquals("789 Pine Rd", parsed["street"])
        assertEquals("Lakeside", parsed["city"])
        assertNull(parsed["region"])
        assertEquals("90210", parsed["postal"])
    }

    @Test
    fun streetOnlyLeavesRestNull() {
        val parsed = parseAddress("1600 Amphitheatre Parkway")
        assertEquals("1600 Amphitheatre Parkway", parsed["street"])
        assertNull(parsed["city"])
        assertNull(parsed["region"])
        assertNull(parsed["postal"])
    }

    @Test
    fun streetAndCityLeaveRegionAndPostalNull() {
        val parsed = parseAddress("123 Main St, Springfield")
        assertEquals("123 Main St", parsed["street"])
        assertEquals("Springfield", parsed["city"])
        assertNull(parsed["region"])
        assertNull(parsed["postal"])
    }

    @Test
    fun extraWhitespaceIsTrimmed() {
        val parsed = parseAddress("  10 Downing St  ,  London  ,  UK  ")
        assertEquals("10 Downing St", parsed["street"])
        assertEquals("London", parsed["city"])
        assertEquals("UK", parsed["region"])
        assertNull(parsed["postal"])
    }

    @Test
    fun emptySegmentsAreIgnored() {
        val parsed = parseAddress("123 Main St,, Springfield, IL 62701")
        assertEquals("123 Main St", parsed["street"])
        assertEquals("Springfield", parsed["city"])
        assertEquals("IL", parsed["region"])
        assertEquals("62701", parsed["postal"])
    }

    @Test
    fun multiWordRegionParses() {
        val parsed = parseAddress("1 Infinite Loop, Cupertino, California 95014")
        assertEquals("California", parsed["region"])
        assertEquals("95014", parsed["postal"])
    }

    @Test
    fun blankInputFails() {
        assertFailsWith<IllegalArgumentException> { parseAddress("") }
        assertFailsWith<IllegalArgumentException> { parseAddress("   ") }
        assertFailsWith<IllegalArgumentException> { parseAddress(" , , ") }
    }
}
