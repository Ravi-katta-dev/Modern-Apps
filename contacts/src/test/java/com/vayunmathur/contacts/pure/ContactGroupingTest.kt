package com.vayunmathur.contacts.pure

import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContactGroupingTest {

    private data class GroupFixture(val name: String, val favorite: Boolean = false)

    private fun groupKey(displayName: String): Char {
        val first = displayName.trim().firstOrNull() ?: return '#'
        val decomposed = Normalizer.normalize(first.toString(), Normalizer.Form.NFD)
        val stripped = decomposed.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        val base = stripped.firstOrNull() ?: first
        val upper = base.uppercaseChar()
        return if (upper.isLetter()) upper else '#'
    }

    private fun groupContacts(
        contacts: List<GroupFixture>,
    ): Pair<List<GroupFixture>, Map<Char, List<GroupFixture>>> {
        val favorites = contacts.filter { it.favorite }
        val grouped = contacts.filter { !it.favorite }.groupBy { groupKey(it.name) }.toSortedMap()
        return favorites to grouped
    }

    @Test
    fun asciiGroupsUnderUppercaseLetter() {
        assertEquals('A', groupKey("alice"))
        assertEquals('B', groupKey("Bob"))
    }

    @Test
    fun accentedGroupsUnderBaseLetter() {
        assertEquals('E', groupKey("Émile"))
        assertEquals('C', groupKey("çan"))
    }

    @Test
    fun nonLettersGroupUnderHash() {
        assertEquals('#', groupKey("3 Doors"))
        assertEquals('#', groupKey("@home"))
        assertEquals('#', groupKey(""))
        assertEquals('#', groupKey("   "))
    }

    @Test
    fun lowercaseAndUppercaseShareGroup() {
        val (_, grouped) = groupContacts(listOf(GroupFixture("amy"), GroupFixture("Amy")))
        assertEquals(2, grouped['A']?.size)
    }

    @Test
    fun favoritesArePartitionedOut() {
        val contacts = listOf(
            GroupFixture("Alice", favorite = true),
            GroupFixture("Bob", favorite = true),
            GroupFixture("Carol"),
        )
        val (favorites, grouped) = groupContacts(contacts)
        assertEquals(2, favorites.size)
        assertEquals(1, grouped.values.sumOf { it.size })
        assertTrue(grouped.values.all { group -> group.none { it.favorite } })
    }

    @Test
    fun groupsAreSortedByKey() {
        val contacts = listOf(GroupFixture("Zed"), GroupFixture("amy"), GroupFixture("3po"), GroupFixture("Bob"))
        val (_, grouped) = groupContacts(contacts)
        assertEquals(listOf('#', 'A', 'B', 'Z'), grouped.keys.toList())
    }

    @Test
    fun cjkNameGroupsUnderOwnCharacter() {
        assertEquals('北', groupKey("北京伟"))
    }
}
