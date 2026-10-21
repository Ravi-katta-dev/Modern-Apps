package com.vayunmathur.contacts.pure

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins vCard text escaping: the backslash/comma/semicolon/newline codec
 * shared by the importer and the exporter.
 *
 * Mirrors the escape/unescape helpers used by util/VcfUtils (export) and
 * util/VcfParser (import): encode escapes backslash first, decode unescapes
 * it last, so a round trip is lossless.
 */
class VCardEscapeTest {
    private fun escapeV(value: String): String {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")
    }

    private fun unescapeV(value: String): String {
        return value
            .replace("\\n", "\n")
            .replace("\\N", "\n")
            .replace("\\,", ",")
            .replace("\\;", ";")
            .replace("\\\\", "\\")
    }

    @Test
    fun plainTextPassesThroughBothWays() {
        assertEquals("John Doe", escapeV("John Doe"))
        assertEquals("John Doe", unescapeV("John Doe"))
    }

    @Test
    fun emptyStringRoundTrips() {
        assertEquals("", escapeV(""))
        assertEquals("", unescapeV(""))
    }

    @Test
    fun commasAndSemicolonsAreEscaped() {
        assertEquals("a\\,b\\;c", escapeV("a,b;c"))
        assertEquals("a,b;c", unescapeV("a\\,b\\;c"))
    }

    @Test
    fun bothNewlineVariantsDecodeToNewline() {
        assertEquals("a\nb", unescapeV("a\\nb"))
        assertEquals("a\nb", unescapeV("a\\Nb"))
    }

    @Test
    fun backslashIsEscapedFirstAndUnescapedLast() {
        assertEquals("a\\\\b", escapeV("a\\b"))
        assertEquals("a\\b", unescapeV("a\\\\b"))
    }

    @Test
    fun literalBackslashNIsNotConfusedWithNewline() {
        assertEquals("a\\\nb", unescapeV("a\\\\\\nb"))
    }

    @Test
    fun fullRoundTripPreservesEverySpecial() {
        val raw = "line1\nline2, with; specials \\ done"
        assertEquals(raw, unescapeV(escapeV(raw)))
    }

    @Test
    fun unicodeTextRoundTripsUntouched() {
        val raw = "José García, 東京; Züri\nch"
        assertEquals(raw, unescapeV(escapeV(raw)))
    }

    @Test
    fun addressStyleValueRoundTrips() {
        val raw = "123 Main St, Apt 4; Springfield"
        assertEquals(raw, unescapeV(escapeV(raw)))
    }

    @Test
    fun unknownEscapeSequenceIsKeptVerbatim() {
        assertEquals("a\\tb", unescapeV("a\\tb"))
    }
}
