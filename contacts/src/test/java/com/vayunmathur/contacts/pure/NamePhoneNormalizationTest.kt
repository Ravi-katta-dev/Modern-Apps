package com.vayunmathur.contacts.pure

import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NamePhoneNormalizationTest {

    private fun displayName(prefix: String, first: String, middle: String, last: String, suffix: String): String {
        return listOf(prefix, first, middle, last, suffix)
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    private fun sortKey(name: String): String {
        val collapsed = name.trim().replace(Regex("\\s+"), " ")
        val decomposed = Normalizer.normalize(collapsed, Normalizer.Form.NFD)
        return decomposed.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "").lowercase()
    }

    private fun normalizePhone(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "phone is blank" }
        val hasPlus = trimmed.startsWith("+")
        val body = if (hasPlus) trimmed.substring(1) else trimmed
        require(body.all { it in '0'..'9' || it in " +-().xX#" }) { "bad characters: $raw" }
        val digits = trimmed.filter { it in '0'..'9' }
        require(digits.length in 7..15) { "bad digit count: $raw" }
        return if (hasPlus) "+$digits" else digits
    }

    private fun formatUsNational(digits: String): String {
        require(digits.length == 10 && digits.all { it in '0'..'9' }) { "need exactly 10 digits" }
        return "(${digits.substring(0, 3)}) ${digits.substring(3, 6)}-${digits.substring(6)}"
    }

    @Test
    fun displayNameSkipsBlanksAndCollapsesSpaces() {
        assertEquals("Ada Love lace", displayName("", "  Ada  ", "", " Love  lace ", ""))
    }

    @Test
    fun unicodeDisplayNameIsPreserved() {
        assertEquals("José 北京伟 Jr.", displayName("", "José", "", "北京伟", "Jr."))
    }

    @Test
    fun emptyPartsGiveEmptyDisplay() {
        assertEquals("", displayName("", "   ", "", "", ""))
    }

    @Test
    fun sortKeyIsDiacriticAndCaseInsensitive() {
        assertEquals("emilie muller", sortKey("  Émilie  MÜLLER "))
    }

    @Test
    fun normalizePhoneStripsSeparators() {
        assertEquals("4155550132", normalizePhone("(415) 555-0132"))
    }

    @Test
    fun normalizePhoneKeepsLeadingPlus() {
        assertEquals("+442071234567", normalizePhone("+44 20 7123 4567"))
    }

    @Test
    fun extensionWithXIsAccepted() {
        assertEquals("4155550132123", normalizePhone("415-555-0132 x123"))
    }

    @Test
    fun invalidPhonesThrow() {
        val bad = listOf("", "   ", "abc", "123456", "1234567890123456", "4155550132abcd")
        for (raw in bad) {
            assertFailsWith<IllegalArgumentException>("input '$raw'") { normalizePhone(raw) }
        }
    }

    @Test
    fun nonAsciiDigitsAreRejected() {
        assertFailsWith<IllegalArgumentException> { normalizePhone("４１５５５５０１３２") }
    }

    @Test
    fun formatUsNationalFormatsTenDigits() {
        assertEquals("(415) 555-0132", formatUsNational("4155550132"))
    }

    @Test
    fun formatUsNationalRejectsBadInput() {
        assertFailsWith<IllegalArgumentException> { formatUsNational("415555013") }
        assertFailsWith<IllegalArgumentException> { formatUsNational("14155550132") }
        assertFailsWith<IllegalArgumentException> { formatUsNational("41555501ab") }
    }
}
