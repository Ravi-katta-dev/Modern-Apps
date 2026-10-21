package com.vayunmathur.notes.data

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
class NoteSyncIdTest {

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
    fun `a fresh note carries a valid sync id and is not deleted`() {
        val note = Note(id = 0, title = "t", content = "c")
        assertEquals(32, note.syncId.length)
        assertTrue(note.syncId.all { it.isDigit() || it in 'a'..'f' })
        assertFalse(note.deleted)
        assertTrue(note.updatedAt > 0)
    }

    @Test
    fun `distinct fresh notes get distinct sync ids`() {
        val first = Note(id = 0, title = "a", content = "")
        val second = Note(id = 0, title = "a", content = "")
        assertTrue(first.syncId != second.syncId)
    }

    @Test
    fun `the note snapshot kind is the relay baseline key`() {
        assertEquals("note", SyncSnapshot.KIND_NOTE)
    }

    @Test
    fun `snapshots keep sync identity plus both clocks`() {
        val snapshot = SyncSnapshot(
            syncId = newSyncId(),
            kind = SyncSnapshot.KIND_NOTE,
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
