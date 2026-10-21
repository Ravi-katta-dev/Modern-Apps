package com.vayunmathur.contacts.pure

import java.text.Collator
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class ContactSortTest {

    private enum class Mode { First, Last }

    private data class SortFixture(val first: String, val last: String)

    private fun displayOf(contact: SortFixture): String {
        return listOf(contact.first, contact.last)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    private fun sortName(contact: SortFixture, mode: Mode): String {
        if (mode == Mode.First) return displayOf(contact)
        val last = contact.last.trim()
        val first = contact.first.trim()
        return when {
            last.isNotEmpty() && first.isNotEmpty() -> "$last $first"
            last.isNotEmpty() -> last
            displayOf(contact).isNotBlank() -> displayOf(contact)
            else -> first
        }
    }

    private fun collator(): Collator {
        return Collator.getInstance(Locale.ROOT).apply {
            strength = Collator.PRIMARY
            decomposition = Collator.CANONICAL_DECOMPOSITION
        }
    }

    private fun sortedByMode(contacts: List<SortFixture>, mode: Mode): List<SortFixture> {
        val col = collator()
        return contacts.map { it to col.getCollationKey(sortName(it, mode)) }
            .sortedWith(compareBy { it.second })
            .map { it.first }
    }

    @Test
    fun sortsByFirstNameIgnoringCase() {
        val contacts = listOf(SortFixture("bob", "B"), SortFixture("alice", "A"), SortFixture("Carol", "C"))
        val result = sortedByMode(contacts, Mode.First)
        assertEquals(listOf("alice", "bob", "Carol"), result.map { it.first })
    }

    @Test
    fun sortsByLastNameMode() {
        val contacts = listOf(SortFixture("Ada", "Zebra"), SortFixture("Bob", "Apple"))
        val result = sortedByMode(contacts, Mode.Last)
        assertEquals(listOf("Apple", "Zebra"), result.map { it.last })
    }

    @Test
    fun accentedSortsWithBaseLetter() {
        val contacts = listOf(SortFixture("Victor", "V"), SortFixture("Véronique", "V"))
        val result = sortedByMode(contacts, Mode.First)
        assertEquals("Véronique", result[0].first)
        assertEquals("Victor", result[1].first)
    }

    @Test
    fun emptyDisplaySortsBeforeLetters() {
        val contacts = listOf(SortFixture("Amy", "A"), SortFixture("", ""))
        val result = sortedByMode(contacts, Mode.First)
        assertEquals("", result[0].first)
        assertEquals("Amy", result[1].first)
    }

    @Test
    fun singleNameFallsBackToFullValue() {
        assertEquals("Madonna", sortName(SortFixture("Madonna", ""), Mode.Last))
    }

    @Test
    fun lastNameModePrefersLastFirst() {
        assertEquals("Doe John", sortName(SortFixture("John", "Doe"), Mode.Last))
    }

    @Test
    fun unicodeSortIsStableAndLossless() {
        val contacts = listOf(SortFixture("伟", "北京"), SortFixture("Amy", "A"), SortFixture("Émile", "B"))
        val once = sortedByMode(contacts, Mode.First)
        val twice = sortedByMode(once, Mode.First)
        assertEquals(once.map { it.first }, twice.map { it.first })
        assertEquals(setOf("伟", "Amy", "Émile"), once.map { it.first }.toSet())
    }
}
