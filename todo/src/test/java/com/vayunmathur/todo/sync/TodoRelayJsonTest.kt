package com.vayunmathur.todo.sync

import com.vayunmathur.todo.data.Todo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the relay envelope mapping between [Todo] and [RelayTodoJson].
 *
 * The envelope is a field-for-field mirror of everysync's `RemoteTodo`; both
 * sides decode with `ignoreUnknownKeys`, so a renamed or dropped field would
 * silently lose data instead of failing. These tests pin every field mapping
 * plus the tombstone and rebind rules from `applyRelayJson`.
 */
class TodoRelayJsonTest {

    @Test
    fun `toRelayJson mirrors every field`() {
        val todo = Todo(
            id = 5,
            title = "title",
            notes = "notes",
            done = true,
            position = 12.5,
            dueAt = 999L,
            createdAt = 111L,
            syncId = "abc123",
            updatedAt = 888L,
            deleted = true,
        )
        val relay = todo.toRelayJson()
        assertEquals("abc123", relay.uid)
        assertEquals("title", relay.title)
        assertEquals("notes", relay.notes)
        assertEquals(true, relay.done)
        assertEquals(999L, relay.dueAt)
        assertEquals(12.5, relay.position)
        assertEquals(111L, relay.createdAt)
        assertEquals(true, relay.deleted)
        assertEquals(888L, relay.updatedAt)
    }

    @Test
    fun `toTodo rebinds onto the existing row id and keeps relay identity`() {
        val relay = RelayTodoJson(
            uid = "sync1",
            title = "t",
            notes = "n",
            done = true,
            dueAt = 50L,
            position = 3.0,
            createdAt = 10L,
            deleted = false,
            updatedAt = 42L,
        )
        val todo = relay.toTodo(77L)
        assertEquals(77L, todo.id)
        assertEquals("sync1", todo.syncId)
        assertEquals("t", todo.title)
        assertEquals("n", todo.notes)
        assertEquals(true, todo.done)
        assertEquals(50L, todo.dueAt)
        assertEquals(3.0, todo.position)
        assertEquals(10L, todo.createdAt)
        assertEquals(42L, todo.updatedAt)
        assertEquals(false, todo.deleted)
    }

    @Test
    fun `a deleted payload maps to a tombstone never a hard delete`() {
        val todo = RelayTodoJson(uid = "gone", deleted = true, updatedAt = 7L).toTodo(1L)
        assertTrue(todo.deleted)
        assertEquals("gone", todo.syncId)
    }

    @Test
    fun `null due dates survive the envelope as null`() {
        val todo = Todo(id = 1, title = "t", dueAt = null).toRelayJson().toTodo(1L)
        assertNull(todo.dueAt)
    }

    @Test
    fun `unicode title and notes survive the envelope`() {
        val relay = Todo(title = "記 😀 مرحبا", notes = "note 😀 тело").toRelayJson()
        val back = relay.toTodo(9L)
        assertEquals("記 😀 مرحبا", back.title)
        assertEquals("note 😀 тело", back.notes)
        assertEquals(9L, back.id)
    }

    @Test
    fun `round trip preserves position extremes and epoch bounds`() {
        val todo = Todo(
            id = 0,
            title = "",
            position = -1e15,
            dueAt = Long.MAX_VALUE,
            createdAt = 0L,
            updatedAt = Long.MAX_VALUE,
        ).toRelayJson().toTodo(0L)
        assertEquals(-1e15, todo.position)
        assertEquals(Long.MAX_VALUE, todo.dueAt)
        assertEquals(Long.MAX_VALUE, todo.updatedAt)
    }

    @Test
    fun `empty envelope maps to blank fields with zero clocks`() {
        val todo = RelayTodoJson().toTodo(3L)
        assertEquals("", todo.syncId)
        assertEquals("", todo.title)
        assertEquals("", todo.notes)
        assertEquals(false, todo.done)
        assertNull(todo.dueAt)
        assertEquals(0.0, todo.position)
        assertEquals(0L, todo.createdAt)
        assertEquals(0L, todo.updatedAt)
        assertEquals(false, todo.deleted)
    }
}
