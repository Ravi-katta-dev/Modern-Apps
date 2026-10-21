package com.vayunmathur.notes.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pins [NoteBlock] defaults and [randomBlockId], plus [Note] position copies.
 *
 * The editor inserts a trailing empty [NoteBlock.Text] after media and moves
 * blocks with [NotePage moveBlock deltas][com.vayunmathur.notes.ui.NotePage],
 * so stable unique ids and value-copy semantics are what those flows rely on.
 */
class NoteBlockTest {

    @Test
    fun `text defaults to empty markdown with a unique id`() {
        val first = NoteBlock.Text()
        val second = NoteBlock.Text()
        assertEquals("", first.markdown)
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun `block ids look like uuid strings`() {
        val id = randomBlockId()
        assertEquals(36, id.length)
        assertEquals(4, id.count { it == '-' })
        assertTrue(id.all { it.isDigit() || it in 'a'..'f' || it == '-' })
    }

    @Test
    fun `generated ids are unique across many blocks`() {
        val ids = List(200) { randomBlockId() }.toSet()
        assertEquals(200, ids.size)
    }

    @Test
    fun `image defaults to full width`() {
        val image = NoteBlock.Image("a.jpg", id = "i")
        assertEquals(1f, image.widthFraction)
        assertEquals("a.jpg", image.fileName)
    }

    @Test
    fun `ink defaults to empty strokes and 300dp height`() {
        val ink = NoteBlock.Ink(id = "k")
        assertEquals(emptyList(), ink.strokes)
        assertEquals(300, ink.heightDp)
    }

    @Test
    fun `note body defaults to no blocks`() {
        assertEquals(emptyList(), NoteBody().blocks)
    }

    @Test
    fun `withPosition copies the note and keeps every other field`() {
        val note = Note(id = 7, title = "t", content = "c", position = 1.0)
        val moved = note.withPosition(2.5)
        assertEquals(2.5, moved.position)
        assertEquals(7L, moved.id)
        assertEquals("t", moved.title)
        assertEquals("c", moved.content)
        assertEquals(1.0, note.position)
    }

    @Test
    fun `unicode file names and markdown survive copies`() {
        val image = NoteBlock.Image("фото-記 😀.jpg", id = "u")
        assertEquals("фото-記 😀.jpg", image.copy(widthFraction = 0.5f).fileName)
        val text = NoteBlock.Text("مرحبا 😀", id = "v")
        assertEquals("مرحبا 😀", text.copy().markdown)
    }
}
