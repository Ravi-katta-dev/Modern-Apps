package com.vayunmathur.contacts.pure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins vCard property-line parsing: NAME[;PARAMS]:VALUE splitting, TYPE
 * defaulting, quoted values, and the N/ADR component shapes plus the
 * numeric/--MM-DD date normalization.
 *
 * Mirrors util/VcfParser's handlePropertyLine/parseParams and its
 * YYYYMMDD to ISO rewrite with the 1604 no-year sentinel.
 */
class VCardPropertyParseTest {
    private data class Prop(val name: String, val params: Map<String, List<String>>, val value: String)

    private fun parseParams(parts: List<String>): Map<String, List<String>> {
        val out = mutableMapOf<String, MutableList<String>>()
        for (p in parts) {
            if (p.isEmpty()) continue
            val eq = p.indexOf('=')
            if (eq == -1) {
                val vals = p.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                out.getOrPut("TYPE") { mutableListOf() }.addAll(vals)
            } else {
                val k = p.take(eq).uppercase()
                val v = p.substring(eq + 1)
                val vals = v.split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
                out.getOrPut(k) { mutableListOf() }.addAll(vals)
            }
        }
        return out
    }

    private fun parseLine(line: String): Prop? {
        val colon = line.indexOf(':')
        if (colon == -1) return null
        val segments = line.take(colon).split(';')
        val name = segments.firstOrNull()?.uppercase() ?: return null
        return Prop(name, parseParams(segments.drop(1)), line.substring(colon + 1))
    }

    private fun normalizeDate(raw: String): String {
        var dv = raw
        if (dv.matches(DATE_REGEX)) {
            dv = dv.take(4) + "-" + dv.substring(4, 6) + "-" + dv.substring(6, 8)
        } else if (dv.startsWith("--")) {
            dv = NO_YEAR_SENTINEL + "-" + dv.substring(2)
        }
        return dv
    }

    @Test
    fun simpleFnParsesWithNoParams() {
        val p = parseLine("FN:Jane Doe")!!
        assertEquals("FN", p.name)
        assertEquals("Jane Doe", p.value)
        assertEquals(emptyMap(), p.params)
    }

    @Test
    fun bareTypeTokenDefaultsToTypeKey() {
        val p = parseLine("TEL;CELL:+15551234567")!!
        assertEquals("TEL", p.name)
        assertEquals(listOf("CELL"), p.params["TYPE"])
        assertEquals("+15551234567", p.value)
    }

    @Test
    fun keyedTypeAcceptsMultipleValues() {
        val p = parseLine("TEL;TYPE=HOME,VOICE:+15551234567")!!
        assertEquals(listOf("HOME", "VOICE"), p.params["TYPE"])
    }

    @Test
    fun quotedParamValuesAreUnquoted() {
        val p = parseLine("EMAIL;TYPE=\"WORK\":a@b.com")!!
        assertEquals(listOf("WORK"), p.params["TYPE"])
    }

    @Test
    fun paramNameIsUppercased() {
        val p = parseLine("TEL;type=cell:123")!!
        assertEquals(listOf("cell"), p.params["TYPE"])
    }

    @Test
    fun lineWithoutColonIsRejected() {
        assertNull(parseLine("FN Jane Doe"))
    }

    @Test
    fun emptyParamSegmentsAreSkipped() {
        val p = parseLine("TEL;;TYPE=CELL:123")!!
        assertEquals(listOf("CELL"), p.params["TYPE"])
    }

    @Test
    fun valueMayContainColons() {
        val p = parseLine("URL:https://example.com/a:b")!!
        assertEquals("URL", p.name)
        assertEquals("https://example.com/a:b", p.value)
    }

    @Test
    fun nameFieldSplitsIntoFiveComponents() {
        val p = parseLine("N:Doe;Jane;Ann;Dr.;Jr.")!!
        assertEquals(listOf("Doe", "Jane", "Ann", "Dr.", "Jr."), p.value.split(';'))
    }

    @Test
    fun addressFieldSplitsIntoSevenComponents() {
        val p = parseLine("ADR;TYPE=HOME:;;123 Main St;Springfield;IL;62701;USA")!!
        val comps = p.value.split(';')
        assertEquals(7, comps.size)
        assertEquals("Springfield", comps[3])
        assertEquals("USA", comps[6])
    }

    @Test
    fun numericDateNormalizesToIso() {
        assertEquals("2026-05-04", normalizeDate("20260504"))
    }

    @Test
    fun noYearDateGetsSentinelYear() {
        assertEquals("1604-05-04", normalizeDate("--05-04"))
    }

    @Test
    fun isoDatePassesThrough() {
        assertEquals("2026-05-04", normalizeDate("2026-05-04"))
    }

    private companion object {
        private val DATE_REGEX = Regex("^\\d{8}")
        private const val NO_YEAR_SENTINEL = "1604"
    }
}
