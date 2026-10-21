package com.vayunmathur.contacts.pure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the contact search filter: per-keystroke token matching against a
 * prebuilt lowercase haystack with phone normalization.
 *
 * Mirrors ContactViewModel's searchHaystack/filterByTokens/
 * haystackMatchesToken/normalizePhoneForCompare (which are bound to the
 * ViewModel) over plain strings so the host JVM can run them.
 */
class ContactSearchFilterTest {
    private val whitespace = Regex("\\s+")

    private fun tokenize(query: String): List<String> =
        query.trim().lowercase().split(whitespace).filter { it.isNotBlank() }

    private fun normalizePhone(raw: String): String =
        raw.filter { it.isDigit() || it == '+' }.trim()

    private fun haystack(vararg fields: String): String {
        val joined = fields.joinToString(" ")
        val normalized = fields.joinToString(" ") { normalizePhone(it) }
        return "$joined $normalized".lowercase()
    }

    private fun matches(haystackLower: String, token: String): Boolean {
        if (haystackLower.contains(token)) return true
        val normalizedToken = normalizePhone(token)
        return normalizedToken.isNotEmpty() && normalizedToken != token &&
            haystackLower.contains(normalizedToken)
    }

    private fun filter(haystacks: List<String>, query: String): List<String> {
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return haystacks
        return haystacks.filter { hay -> tokens.all { matches(hay, it) } }
    }

    @Test
    fun emptyQueryMatchesEverything() {
        val hay = listOf(haystack("Jane Doe"), haystack("John Smith"))
        assertEquals(hay, filter(hay, ""))
        assertEquals(hay, filter(hay, "   "))
    }

    @Test
    fun singleTokenMatchesSubstringCaseInsensitively() {
        // Callers lowercase before matching (see tokenize); the matcher
        // itself is case-sensitive, mirroring ContactViewModel.
        val hay = haystack("Jane Doe", "jane@x.com", "Acme")
        assertTrue(matches(hay, "jane"))
        assertTrue(matches(hay, "acm"))
        assertFalse(matches(hay, "bob"))
    }

    @Test
    fun multipleTokensMustAllMatch() {
        val hay = haystack("Jane Marie Doe")
        assertTrue(filter(listOf(hay), "jane doe").isNotEmpty())
        assertTrue(filter(listOf(hay), "jane smith").isEmpty())
    }

    @Test
    fun tokensSplitOnAnyWhitespace() {
        assertEquals(listOf("a", "b"), tokenize("  a\tb\n "))
    }

    @Test
    fun phoneWithPunctuationMatchesDigitsOnlyQuery() {
        val hay = haystack("Jane", "(555) 123-4567")
        assertTrue(matches(hay, "5551234567"))
        assertTrue(matches(hay, "(555) 123-4567"))
    }

    @Test
    fun plusPrefixedQueryMatchesNormalizedNumber() {
        val hay = haystack("Jane", "+15551234567")
        assertTrue(matches(hay, "+15551234567"))
    }

    @Test
    fun tokenThatIsOnlyPunctuationNeverMatches() {
        assertFalse(matches(haystack("Jane"), "+++"))
        assertFalse(matches("", "555"))
    }

    @Test
    fun unicodeNamesMatchAfterLowercasing() {
        val hay = haystack("José García")
        assertTrue(matches(hay, "josé"))
        assertTrue(matches(hay, "garcía"))
    }

    @Test
    fun filterKeepsOnlyMatchingContacts() {
        val hay = listOf(haystack("Jane Doe"), haystack("John Smith"), haystack("Janet Doe"))
        assertEquals(2, filter(hay, "doe").size)
        // Substring matching: both Jane and Janet contain "jane" and "doe".
        assertEquals(2, filter(hay, "jane doe").size)
        assertEquals(1, filter(hay, "janet").size)
        assertEquals(0, filter(hay, "doe smith").size)
    }

    @Test
    fun normalizePhoneStripsSeparatorsButKeepsPlus() {
        assertEquals("+15551234567", normalizePhone(" +1 (555) 123-4567 "))
        assertEquals("", normalizePhone(" - () "))
    }
}
