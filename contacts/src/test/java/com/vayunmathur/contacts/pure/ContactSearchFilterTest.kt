package com.vayunmathur.contacts.pure

import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContactSearchFilterTest {

    private data class SearchFixture(
        val name: String,
        val phone: String = "",
        val email: String = "",
        val nickname: String = "",
    )

    private fun foldText(value: String): String {
        val decomposed = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
        return decomposed.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "").lowercase()
    }

    private fun digitsOf(value: String): String = value.filter { it in '0'..'9' }

    private fun matches(contact: SearchFixture, query: String): Boolean {
        if (query.isBlank()) return true
        val folded = foldText(query)
        if (foldText(contact.name).contains(folded)) return true
        if (foldText(contact.email).contains(folded)) return true
        if (contact.nickname.isNotBlank() && foldText(contact.nickname).contains(folded)) return true
        val queryDigits = digitsOf(query)
        if (queryDigits.isNotEmpty() && digitsOf(contact.phone).contains(queryDigits)) return true
        return false
    }

    private fun filter(contacts: List<SearchFixture>, query: String): List<SearchFixture> {
        return contacts.filter { matches(it, query) }
    }

    private fun fixtures(): List<SearchFixture> {
        return listOf(
            SearchFixture("Alice Anderson", "4155550132", "alice@example.com"),
            SearchFixture("Émilie Bernard", "5105550199", "emilie@example.fr", "Mimi"),
            SearchFixture("Robert Chen", "6505550123", "robert@example.com", "Bobby"),
            SearchFixture("北京伟", "2125550145", "bei@example.cn"),
        )
    }

    @Test
    fun emptyQueryReturnsEverything() {
        assertEquals(4, filter(fixtures(), "").size)
        assertEquals(4, filter(fixtures(), "   ").size)
    }

    @Test
    fun nameSearchIgnoresCase() {
        val result = filter(fixtures(), "ALI")
        assertEquals(listOf("Alice Anderson"), result.map { it.name })
    }

    @Test
    fun diacriticInsensitiveNameSearch() {
        val result = filter(fixtures(), "emilie")
        assertEquals(listOf("Émilie Bernard"), result.map { it.name })
    }

    @Test
    fun phoneSearchIgnoresSeparators() {
        assertEquals(listOf("Alice Anderson"), filter(fixtures(), "(415)").map { it.name })
        assertEquals(listOf("Robert Chen"), filter(fixtures(), "650-555").map { it.name })
    }

    @Test
    fun emailSearchMatchesDomain() {
        assertEquals(listOf("Émilie Bernard"), filter(fixtures(), "example.fr").map { it.name })
    }

    @Test
    fun nicknameSearchFindsContact() {
        assertEquals(listOf("Robert Chen"), filter(fixtures(), "bobby").map { it.name })
    }

    @Test
    fun sharedDomainMatchesEveryone() {
        assertEquals(4, filter(fixtures(), "example").size)
    }

    @Test
    fun unicodeQueryMatches() {
        val result = filter(fixtures(), "北京")
        assertEquals(listOf("北京伟"), result.map { it.name })
    }

    @Test
    fun noMatchGivesEmpty() {
        assertTrue(filter(fixtures(), "zzz").isEmpty())
    }
}
