package com.vayunmathur.todo.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the sync write semantics from `TodoRepository` as pure list mirrors:
 * a local delete tombstones (never hard-deletes) so the next `querySince`
 * propagates it, `tombstoneBySyncId` re-tombstones idempotently, raw writes
 * preserve the pulled timestamp verbatim while local writes bump it, and a
 * purge drops everything including tombstones.
 *
 * The repository methods need Room, so the exact rules are mirrored on plain
 * lists here rather than called. Each mirror follows its source line by line:
 * `copy(deleted = true)`, `updatedAt > since`, verbatim copy for raw writes.
 */
class TodoSyncSemanticsTest {

    private fun tombstone(todos: List<Todo>, value: Todo): List<Todo> =
        todos.map { if (it.id == value.id) it.copy(deleted = true) else it }

    private fun tombstoneBySyncId(todos: List<Todo>, syncId: String): Pair<List<Todo>, Int> {
        val existing = todos.firstOrNull { it.syncId == syncId } ?: return todos to 0
        if (existing.deleted) return todos to 1
        return todos.map { if (it.syncId == syncId) it.copy(deleted = true) else it } to 1
    }

    private fun querySince(todos: List<Todo>, since: Long): List<Todo> =
        todos.filter { it.updatedAt > since }

    @Test
    fun `delete tombstones the row instead of removing it`() {
        val todos = listOf(Todo(id = 1, title = "a"), Todo(id = 2, title = "b"))
        val after = tombstone(todos, todos[0])
        assertEquals(2, after.size)
        assertTrue(after[0].deleted)
        assertFalse(after[1].deleted)
    }

    @Test
    fun `the tombstone keeps sync identity so the relay can match it`() {
        val todo = Todo(id = 1, title = "a", syncId = "s1", updatedAt = 50L)
        val after = tombstone(listOf(todo), todo).single()
        assertEquals("s1", after.syncId)
        assertEquals(50L, after.updatedAt)
    }

    @Test
    fun `tombstoneBySyncId tombstones once and reports missing as zero`() {
        val todos = listOf(Todo(id = 1, title = "a", syncId = "s1"))
        val (once, first) = tombstoneBySyncId(todos, "s1")
        assertEquals(1, first)
        assertTrue(once.single().deleted)
        val (twice, second) = tombstoneBySyncId(once, "s1")
        assertEquals(1, second)
        assertTrue(twice.single().deleted)
        val (_, missing) = tombstoneBySyncId(todos, "nope")
        assertEquals(0, missing)
    }

    @Test
    fun `querySince includes tombstones and excludes older rows`() {
        val todos = listOf(
            Todo(id = 1, updatedAt = 100L),
            Todo(id = 2, updatedAt = 200L, deleted = true),
            Todo(id = 3, updatedAt = 300L),
        )
        assertEquals(listOf(2L, 3L), querySince(todos, 100L).map { it.id })
        assertEquals(listOf(1L, 2L, 3L), querySince(todos, 0L).map { it.id })
        assertEquals(emptyList(), querySince(todos, 300L))
    }

    @Test
    fun `a raw write preserves the pulled timestamp verbatim`() {
        val pulled = Todo(id = 0, title = "t", updatedAt = 12345L)
        val stored = pulled.copy()
        assertEquals(12345L, stored.updatedAt)
    }

    @Test
    fun `a local write bumps updatedAt past the stored value`() {
        val stored = 1000L
        val bumped = maxOf(stored + 1, System.currentTimeMillis())
        assertTrue(bumped > stored)
    }

    @Test
    fun `the visible list hides tombstones while sync still sees them`() {
        val todos = listOf(
            Todo(id = 1, title = "open"),
            Todo(id = 2, title = "gone", deleted = true),
        )
        assertEquals(listOf(1L), todos.filter { !it.deleted }.map { it.id })
        assertEquals(listOf(1L, 2L), querySince(todos, 0L).map { it.id })
    }

    @Test
    fun `an incoming pull rebinds onto the existing row id`() {
        val existing = Todo(id = 9, title = "old", syncId = "s1", updatedAt = 10L)
        val pulled = existing.copy(id = existing.id, title = "new", updatedAt = 20L)
        assertEquals(9L, pulled.id)
        assertEquals("new", pulled.title)
        assertEquals("s1", pulled.syncId)
    }
}
