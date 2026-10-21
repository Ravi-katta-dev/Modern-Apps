package com.vayunmathur.notes.sync

import com.vayunmathur.notes.data.Note
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the relay envelope mapping between [Note] and [RelayNoteJson].
 *
 * The envelope is a field-for-field mirror of everysync's `RemoteNote`; both
 * sides decode with `ignoreUnknownKeys`, so a renamed or dropped field would
 * silently lose data instead of failing. These tests pin every field mapping
 * plus the tombstone and rebind rules from `applyRelayJson`.
 */
class NoteRelayJsonTest {

    @Test
    fun `toRelayJson mirrors every field`() {
        val note = Note(
            id = 5,
            title = "title",
            content = "body",
            position = 12.5,
            blocks = """{"blocks":[]}""",
            syncId = "abc123",
            updatedAt = 999L,
            deleted = true,
        )
        val relay = note.toRelayJson()
        assertEquals("abc123", relay.uid)
        assertEquals("title", relay.title)
        assertEquals("body", relay.content)
        assertEquals("""{"blocks":[]}""", relay.blocksJson)
        assertEquals(12.5, relay.position)
        assertEquals(true, relay.deleted)
        assertEquals(999L, relay.updatedAt)
    }

    @Test
    fun `toNote rebinds onto the existing row id and keeps relay identity`() {
        val relay = RelayNoteJson(
            uid = "sync1",
            title = "t",
            content = "c",
            blocksJson = null,
            position = 3.0,
            deleted = false,
            updatedAt = 42L,
        )
        val note = relay.toNote(77L)
        assertEquals(77L, note.id)
        assertEquals("sync1", note.syncId)
        assertEquals("t", note.title)
        assertEquals("c", note.content)
        assertNull(note.blocks)
        assertEquals(3.0, note.position)
        assertEquals(42L, note.updatedAt)
        assertEquals(false, note.deleted)
    }

    @Test
    fun `a deleted payload maps to a tombstone never a hard delete`() {
        val relay = RelayNoteJson(uid = "gone", deleted = true, updatedAt = 7L)
        val note = relay.toNote(1L)
        assertTrue(note.deleted)
        assertEquals("gone", note.syncId)
    }

    @Test
    fun `legacy text-only notes keep null blocks through the envelope`() {
        val note = Note(id = 1, title = "t", content = "plain", blocks = null)
        val relay = note.toRelayJson()
        assertNull(relay.blocksJson)
        val back = relay.toNote(1L)
        assertNull(back.blocks)
        assertEquals("plain", back.content)
    }

    @Test
    fun `unicode title content and block names survive the envelope`() {
        val blocks = """{"blocks":[{"type":"image","fileName":"фото 😀.jpg","id":"b"}]}"""
        val note = Note(id = 1, title = "記 😀 مرحبا", content = "body 😀", blocks = blocks)
        val back = note.toRelayJson().toNote(9L)
        assertEquals("記 😀 مرحبا", back.title)
        assertEquals("body 😀", back.content)
        assertEquals(blocks, back.blocks)
        assertEquals(9L, back.id)
    }

    @Test
    fun `round trip preserves position extremes and epoch bounds`() {
        val note = Note(
            id = 0,
            title = "",
            content = "",
            position = -1e15,
            syncId = "x",
            updatedAt = Long.MAX_VALUE,
        )
        val back = note.toRelayJson().toNote(0L)
        assertEquals(-1e15, back.position)
        assertEquals(Long.MAX_VALUE, back.updatedAt)
    }

    @Test
    fun `empty envelope maps to blank fields with zero clocks`() {
        val note = RelayNoteJson().toNote(3L)
        assertEquals("", note.syncId)
        assertEquals("", note.title)
        assertEquals("", note.content)
        assertEquals(0.0, note.position)
        assertEquals(0L, note.updatedAt)
        assertEquals(false, note.deleted)
    }
}
