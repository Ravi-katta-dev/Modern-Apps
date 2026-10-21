package com.vayunmathur.todo.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the list search predicate from `TodoListScreen`: a blank query keeps
 * every row, otherwise a row matches when its title contains the query,
 * case-insensitively.
 *
 * The predicate lives inline in a composable, so it is mirrored here rather
 * than called. The mirror is exact: blank check, then
 * `title.contains(query, true)`. Unlike notes, only the title is searched —
 * notes text is intentionally not matched.
 */
class TodoSearchFilterTest {

    private fun filter(todos: List<Todo>, query: String): List<Todo> {
        if (query.isBlank()) return todos
        return todos.filter { it.title.contains(query, true) }
    }

    private fun todo(id: Long, title: String, notes: String = "") =
        Todo(id = id, title = title, notes = notes)

    @Test
    fun `blank query keeps every todo in order`() {
        val todos = listOf(todo(1, "a"), todo(2, "b"))
        assertEquals(todos, filter(todos, ""))
        assertEquals(todos, filter(todos, "   "))
    }

    @Test
    fun `match is case-insensitive over the title`() {
        val todos = listOf(
            todo(1, "Buy Milk"),
            todo(2, "buy eggs"),
            todo(3, "Work"),
        )
        assertEquals(listOf(1L, 2L), filter(todos, "buy").map { it.id })
        assertEquals(listOf(1L, 2L), filter(todos, "BUY").map { it.id })
    }

    @Test
    fun `notes text is not searched only the title`() {
        val todos = listOf(todo(1, "Groceries", notes = "buy milk and eggs"))
        assertEquals(emptyList(), filter(todos, "milk"))
        assertEquals(listOf(1L), filter(todos, "groc").map { it.id })
    }

    @Test
    fun `no match returns an empty list`() {
        assertEquals(emptyList(), filter(listOf(todo(1, "a")), "zzz"))
    }

    @Test
    fun `empty todo list stays empty for any query`() {
        assertEquals(emptyList(), filter(emptyList(), "a"))
        assertEquals(emptyList(), filter(emptyList(), ""))
    }

    @Test
    fun `unicode queries match unicode titles`() {
        val todos = listOf(
            todo(1, "買う 記"),
            todo(2, "مرحبا task"),
            todo(3, "emoji 😀 run"),
        )
        assertEquals(listOf(1L), filter(todos, "記").map { it.id })
        assertEquals(listOf(2L), filter(todos, "مرحبا").map { it.id })
        assertEquals(listOf(3L), filter(todos, "😀").map { it.id })
    }

    @Test
    fun `blank titles never match a non-blank query`() {
        val todos = listOf(todo(1, ""), todo(2, "x"))
        assertTrue(filter(todos, "x").map { it.id } == listOf(2L))
    }
}
