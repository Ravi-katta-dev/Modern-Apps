package com.vayunmathur.notes.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the list search predicate from `NotesListScreen`: a blank query keeps
 * every row, otherwise a row matches when its `toString` contains the query,
 * case-insensitively.
 *
 * The predicate lives inline in a composable, so it is mirrored here rather
 * than called. The mirror is exact: blank check, then `contains(..., true)`.
 * `toString` is the data-class rendering, which embeds title and content, so
 * both fields stay searchable.
 */
class NoteSearchFilterTest {

    private fun filter(notes: List<Note>, query: String): List<Note> {
        if (query.isBlank()) return notes
        return notes.filter { it.toString().contains(query, true) }
    }

    private fun note(id: Long, title: String, content: String) =
        Note(id = id, title = title, content = content)

    @Test
    fun `blank query keeps every note in order`() {
        val notes = listOf(note(1, "a", "x"), note(2, "b", "y"))
        assertEquals(notes, filter(notes, ""))
        assertEquals(notes, filter(notes, "   "))
    }

    @Test
    fun `match is case-insensitive over title and content`() {
        val notes = listOf(
            note(1, "Shopping", "eggs"),
            note(2, "Work", "SHOPPING list"),
            note(3, "Other", "nothing"),
        )
        assertEquals(listOf(1L, 2L), filter(notes, "shopping").map { it.id })
        assertEquals(listOf(1L, 2L), filter(notes, "SHOPPING").map { it.id })
    }

    @Test
    fun `no match returns an empty list`() {
        val notes = listOf(note(1, "a", "b"))
        assertEquals(emptyList(), filter(notes, "zzz"))
    }

    @Test
    fun `empty note list stays empty for any query`() {
        assertEquals(emptyList(), filter(emptyList(), "a"))
        assertEquals(emptyList(), filter(emptyList(), ""))
    }

    @Test
    fun `unicode queries match unicode titles`() {
        val notes = listOf(
            note(1, "記メモ", "plain"),
            note(2, "plain", "مرحبا world"),
            note(3, "emoji 😀 title", "x"),
        )
        assertEquals(listOf(1L), filter(notes, "記").map { it.id })
        assertEquals(listOf(2L), filter(notes, "مرحبا").map { it.id })
        assertEquals(listOf(3L), filter(notes, "😀").map { it.id })
    }

    @Test
    fun `query with surrounding spaces matches literally like the screen does`() {
        val notes = listOf(note(1, "a b", "c"))
        assertEquals(emptyList(), filter(notes, " a "))
        assertEquals(listOf(1L), filter(notes, "a b").map { it.id })
    }

    @Test
    fun `multiline content is searchable through the newline`() {
        val notes = listOf(note(1, "t", "line one\nline two"))
        assertTrue(filter(notes, "line two").isNotEmpty())
        assertTrue(filter(notes, "LINE ONE").isNotEmpty())
    }
}
