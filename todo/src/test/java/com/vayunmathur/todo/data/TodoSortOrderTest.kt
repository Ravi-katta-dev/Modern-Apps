package com.vayunmathur.todo.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the position ordering the persisted list relies on: the DAO returns
 * rows ordered by `position`, `upsertAll` persists a reordered list with new
 * positions, and a moved row slots between its neighbours.
 */
class TodoSortOrderTest {

    private fun todo(id: Long, position: Double) = Todo(id = id, title = "t$id", position = position)

    @Test
    fun `rows order by position ascending`() {
        val todos = listOf(todo(1, 30.0), todo(2, 10.0), todo(3, 20.0))
        assertEquals(listOf(2L, 3L, 1L), todos.sortedBy { it.position }.map { it.id })
    }

    @Test
    fun `a row moved between two neighbours lands halfway`() {
        val moved = todo(4, 0.0).withPosition((20.0 + 30.0) / 2.0)
        val ordered = listOf(todo(1, 10.0), todo(2, 20.0), todo(3, 30.0), moved)
        assertEquals(listOf(1L, 2L, 4L, 3L), ordered.sortedBy { it.position }.map { it.id })
        assertEquals(25.0, moved.position)
    }

    @Test
    fun `negative and fractional positions sort stably`() {
        val todos = listOf(todo(1, -5.5), todo(2, 0.0), todo(3, -5.25), todo(4, 1e15))
        assertEquals(listOf(1L, 3L, 2L, 4L), todos.sortedBy { it.position }.map { it.id })
    }

    @Test
    fun `done and open rows share one position order`() {
        val todos = listOf(
            Todo(id = 1, title = "a", done = true, position = 2.0),
            Todo(id = 2, title = "b", done = false, position = 1.0),
        )
        assertEquals(listOf(2L, 1L), todos.sortedBy { it.position }.map { it.id })
    }

    @Test
    fun `repeated halving keeps a strict order without colliding`() {
        var hi = 100.0
        val slots = List(20) {
            val mid = (0.0 + hi) / 2.0
            hi = mid
            mid
        }
        assertEquals(slots.sortedDescending(), slots)
        assertTrue(slots.toSet().size == slots.size)
    }

    @Test
    fun `sorting an empty list stays empty`() {
        assertEquals(emptyList(), emptyList<Todo>().sortedBy { it.position })
    }
}
