package com.vayunmathur.notes.data

import com.vayunmathur.library.ink.SerializedPoint
import com.vayunmathur.library.ink.SerializedStroke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins [Note.body] and [Note.withBody], the plain-text bridge the list preview,
 * search, sharing, and assistant intents all read through.
 *
 * NotesListScreen filters on `note.toString()`, which embeds [Note.content], so
 * [withBody] keeping `content` in sync with the text blocks is load-bearing: a
 * drift would make freshly edited text unsearchable.
 *
 * The corrupt-JSON fallback inside [Note.body] is deliberately not exercised
 * here: it logs through the platform logger, which is unavailable to a JVM
 * unit test. Only paths that never touch the logger are covered.
 */
class NoteBodyTest {

    @Test
    fun `null blocks read back as a single text block`() {
        val note = Note(id = 1, title = "t", content = "hello", blocks = null)
        val body = note.body()
        assertEquals(1, body.blocks.size)
        assertEquals("hello", assertIs<NoteBlock.Text>(body.blocks.single()).markdown)
    }

    @Test
    fun `blank blocks read back as a single text block`() {
        for (blank in listOf("", "   ", "\n\t ")) {
            val note = Note(id = 1, title = "t", content = "legacy", blocks = blank)
            val text = assertIs<NoteBlock.Text>(note.body().blocks.single())
            assertEquals("legacy", text.markdown, "blank <$blank>")
        }
    }

    @Test
    fun `legacy content is used verbatim including newlines and unicode`() {
        val content = "line one\nline two 記 😀"
        val note = Note(id = 1, title = "t", content = content, blocks = null)
        assertEquals(content, assertIs<NoteBlock.Text>(note.body().blocks.single()).markdown)
    }

    @Test
    fun `withBody joins text blocks with a blank line and trims`() {
        val note = Note(id = 1, title = "t", content = "")
        val updated = note.withBody(
            NoteBody(
                blocks = listOf(
                    NoteBlock.Text("  first  ", id = "a"),
                    NoteBlock.Text("second", id = "b"),
                ),
            ),
        )
        assertEquals("first\n\nsecond", updated.content)
    }

    @Test
    fun `withBody ignores image and ink blocks when syncing content`() {
        val note = Note(id = 1, title = "t", content = "")
        val updated = note.withBody(
            NoteBody(
                blocks = listOf(
                    NoteBlock.Image("pic.jpg", id = "i"),
                    NoteBlock.Text("kept", id = "t"),
                    NoteBlock.Ink(id = "k"),
                ),
            ),
        )
        assertEquals("kept", updated.content)
    }

    @Test
    fun `a body with no text blocks stores empty content`() {
        val note = Note(id = 1, title = "t", content = "stale").withBody(NoteBody())
        assertEquals("", note.content)
    }

    @Test
    fun `withBody round-trips mixed blocks through body with ids and order intact`() {
        val stroke = SerializedStroke(
            brushFamily = "pressurePen",
            brushSize = 6f,
            brushColor = -16777216,
            brushEpsilon = 0.1f,
            points = listOf(SerializedPoint(1f, 2f, 10L), SerializedPoint(3f, 4f, 20L)),
        )
        val original = NoteBody(
            blocks = listOf(
                NoteBlock.Text("hello", id = "t1"),
                NoteBlock.Image("abc.jpg", widthFraction = 0.5f, id = "i1"),
                NoteBlock.Ink(listOf(stroke), heightDp = 250, id = "k1"),
                NoteBlock.Text("world", id = "t2"),
            ),
        )
        val stored = Note(id = 1, title = "t", content = "").withBody(original)
        val read = stored.body()
        assertEquals(4, read.blocks.size)
        assertEquals("t1", read.blocks[0].id)
        assertEquals("i1", read.blocks[1].id)
        assertEquals("k1", read.blocks[2].id)
        assertEquals("t2", read.blocks[3].id)
        val image = assertIs<NoteBlock.Image>(read.blocks[1])
        assertEquals("abc.jpg", image.fileName)
        assertEquals(0.5f, image.widthFraction)
        val ink = assertIs<NoteBlock.Ink>(read.blocks[2])
        assertEquals(250, ink.heightDp)
        assertEquals(listOf(1f to 2f, 3f to 4f), ink.strokes.single().points.map { it.x to it.y })
        assertEquals("hello\n\nworld", stored.content)
    }

    @Test
    fun `unknown fields from older builds are ignored instead of failing`() {
        val json = """{"blocks":[{"type":"text","markdown":"hi","id":"a"}],""" +
            """"floating":[1,2]}"""
        val note = Note(id = 1, title = "t", content = "hi", blocks = json)
        val text = assertIs<NoteBlock.Text>(note.body().blocks.single())
        assertEquals("hi", text.markdown)
    }

    @Test
    fun `unknown fields inside a block are ignored`() {
        val json = """{"blocks":[{"type":"image","fileName":"a.jpg","id":"b","legacy":true}]}"""
        val note = Note(id = 1, title = "t", content = "", blocks = json)
        val image = assertIs<NoteBlock.Image>(note.body().blocks.single())
        assertEquals("a.jpg", image.fileName)
    }

    @Test
    fun `content-addressed image names survive a round trip byte for byte`() {
        val name = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855.jpg"
        val stored = Note(id = 1, title = "t", content = "")
            .withBody(NoteBody(listOf(NoteBlock.Image(name, id = "i"))))
        val image = assertIs<NoteBlock.Image>(stored.body().blocks.single())
        assertEquals(name, image.fileName)
        assertTrue(stored.blocks.orEmpty().contains(name))
    }

    @Test
    fun `unicode markdown survives a round trip`() {
        val markdown = "# 記 😀 مرحبا \u202Ereversed\u202C **bold**"
        val stored = Note(id = 1, title = "t", content = "")
            .withBody(NoteBody(listOf(NoteBlock.Text(markdown, id = "u"))))
        assertEquals(markdown, stored.content)
        val text = assertIs<NoteBlock.Text>(stored.body().blocks.single())
        assertEquals(markdown, text.markdown)
    }
}
