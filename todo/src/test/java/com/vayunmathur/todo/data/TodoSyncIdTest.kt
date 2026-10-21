package com.vayunmathur.todo.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [newSyncId] and the [SyncSnapshot] kind, the relay identity every
 * merge decision keys on.
 *
 * A malformed id would silently fork a row from its relay entry, so the
 * format (32 lowercase hex chars, no dashes) is pinned exactly, not just
 * "non-blank".
 */
class TodoSyncIdTest {

    @Test
    fun `sync ids are 32 lowercase hex chars with no dashes`() {
        repeat(50) {
            val id = newSyncId()
            assertEquals(32, id.length, "id <$id>")
            assertTrue(id.all { it.isDigit() || it in 'a'..'f' }, "id <$id>")
            assertFalse(id.contains("-"))
        }
    }

    @Test
    fun `sync ids are unique across many generations`() {
        val ids = List(500) { newSyncId() }.toSet()
        assertEquals(500, ids.size)
    }

    @Test
    fun `a fresh todo carries a valid sync id and is not deleted`() {
        val todo = Todo(title = "t")
        assertEquals(32, todo.syncId.length)
        assertTrue(todo.syncId.all { it.isDigit() || it in 'a'..'f' })
        assertFalse(todo.deleted)
    }

    @Test
    fun `distinct fresh todos get distinct sync ids`() {
        assertTrue(Todo(title = "a").syncId != Todo(title = "a").syncId)
    }

    @Test
    fun `the todo snapshot kind is the relay baseline key`() {
        assertEquals("todo", SyncSnapshot.KIND_TODO)
    }

    @Test
    fun `snapshots keep sync identity plus both clocks`() {
        val snapshot = SyncSnapshot(
            syncId = newSyncId(),
            kind = SyncSnapshot.KIND_TODO,
            contentHash = "abc",
            localUpdatedAt = 100L,
            remoteModified = 200L,
            lastSyncedAt = 300L,
        )
        assertEquals(32, snapshot.syncId.length)
        assertEquals(100L, snapshot.localUpdatedAt)
        assertEquals(200L, snapshot.remoteModified)
        assertEquals(300L, snapshot.lastSyncedAt)
    }
}
