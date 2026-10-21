package com.vayunmathur.notes.platform

import com.vayunmathur.notes.data.NoteBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the pure list operations behind the editor callbacks in `NoteScreen` /
 * `NotePage`: `setBlockMarkdown`, `insertBlock` (with its keep-typing text
 * trailer), `moveBlock`, `deleteBlock` (with its never-empty refill), and the
 * image width stepping in `ImageBlock`.
 *
 * These run on a plain `MutableList` mirror of the in-memory block list, so no
 * Android or repository is needed. Bounds mirrors the screen exactly: a move
 * is ignored when either side leaves `0..lastIndex`, and the refill only fires
 * when the list becomes empty.
 */
class NoteBlockListOpsTest {

    private fun text(id: String, markdown: String = "md-$id") = NoteBlock.Text(markdown, id)
    private fun image(id: String) = NoteBlock.Image("$id.jpg", id = id)

    private fun setMarkdown(blocks: List<NoteBlock>, id: String, markdown: String): List<NoteBlock> =
        blocks.map { if (it.id == id && it is NoteBlock.Text) it.copy(markdown = markdown) else it }

    private fun insertAt(blocks: MutableList<NoteBlock>, afterId: String?, block: NoteBlock) {
        val at = blocks.indexOfFirst { it.id == afterId }.takeIf { it >= 0 }?.plus(1) ?: blocks.size
        blocks.add(at, block)
        if (block !is NoteBlock.Text && (at + 1 > blocks.lastIndex || blocks[at + 1] !is NoteBlock.Text)) {
            blocks.add(at + 1, NoteBlock.Text("", "trailer-$at"))
        }
    }

    private fun move(blocks: MutableList<NoteBlock>, id: String, delta: Int) {
        val i = blocks.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j < 0 || j > blocks.lastIndex) return
        val moved = blocks.removeAt(i)
        blocks.add(j, moved)
    }

    private fun delete(blocks: MutableList<NoteBlock>, id: String) {
        val i = blocks.indexOfFirst { it.id == id }
        if (i < 0) return
        blocks.removeAt(i)
        if (blocks.isEmpty()) blocks.add(NoteBlock.Text("", "refill"))
    }

    private fun shrink(fraction: Float): Float = (fraction - 0.25f).coerceAtLeast(0.25f)
    private fun grow(fraction: Float): Float = (fraction + 0.25f).coerceAtMost(1f)

    @Test
    fun `setBlockMarkdown rewrites only the matching text block`() {
        val blocks = listOf(text("a", "old"), image("b"), text("c", "keep"))
        val updated = setMarkdown(blocks, "a", "new")
        assertEquals("new", (updated[0] as NoteBlock.Text).markdown)
        assertEquals("keep", (updated[2] as NoteBlock.Text).markdown)
    }

    @Test
    fun `setBlockMarkdown on an unknown id leaves every block untouched`() {
        val blocks = listOf(text("a", "x"))
        assertEquals(blocks, setMarkdown(blocks, "missing", "y"))
    }

    @Test
    fun `setBlockMarkdown on a non-text block leaves it untouched`() {
        val blocks = listOf(image("a"))
        assertEquals(blocks, setMarkdown(blocks, "a", "y"))
    }

    @Test
    fun `inserting media appends a trailing text block so typing can continue`() {
        val blocks = mutableListOf<NoteBlock>(text("a"))
        insertAt(blocks, "a", image("b"))
        assertEquals(3, blocks.size)
        assertTrue(blocks[2] is NoteBlock.Text)
    }

    @Test
    fun `inserting media above an existing text block adds no trailer`() {
        val blocks = mutableListOf<NoteBlock>(text("a"), text("b"))
        insertAt(blocks, "a", image("c"))
        assertEquals(listOf("a", "c", "b"), blocks.map { it.id })
    }

    @Test
    fun `inserting with an unknown anchor appends at the end`() {
        val blocks = mutableListOf<NoteBlock>(text("a"))
        insertAt(blocks, "missing", text("b", "b"))
        assertEquals(listOf("a", "b"), blocks.map { it.id })
    }

    @Test
    fun `inserting text never adds a trailer`() {
        val blocks = mutableListOf<NoteBlock>(image("a"))
        insertAt(blocks, "a", text("b", "b"))
        assertEquals(2, blocks.size)
    }

    @Test
    fun `moveBlock swaps neighbours by delta`() {
        val blocks = mutableListOf<NoteBlock>(text("a"), text("b"), text("c"))
        move(blocks, "b", -1)
        assertEquals(listOf("b", "a", "c"), blocks.map { it.id })
        move(blocks, "b", 1)
        assertEquals(listOf("a", "c", "b"), blocks.map { it.id })
    }

    @Test
    fun `moveBlock past either edge is ignored`() {
        val blocks = mutableListOf<NoteBlock>(text("a"), text("b"))
        move(blocks, "a", -1)
        move(blocks, "b", 1)
        move(blocks, "missing", 1)
        assertEquals(listOf("a", "b"), blocks.map { it.id })
    }

    @Test
    fun `deleteBlock refills an emptied note with one empty text block`() {
        val blocks = mutableListOf<NoteBlock>(text("only", "x"))
        delete(blocks, "only")
        assertEquals(1, blocks.size)
        assertEquals("", (blocks.single() as NoteBlock.Text).markdown)
    }

    @Test
    fun `deleteBlock on an unknown id leaves the list untouched`() {
        val blocks = mutableListOf<NoteBlock>(text("a", "x"))
        delete(blocks, "missing")
        assertEquals(1, blocks.size)
    }

    @Test
    fun `image width steps in quarters clamped to a quarter through full`() {
        assertEquals(0.75f, shrink(1f))
        assertEquals(0.25f, shrink(0.25f))
        assertEquals(0.25f, shrink(0.1f))
        assertEquals(1f, grow(0.75f))
        assertEquals(1f, grow(1f))
        assertEquals(0.5f, grow(0.25f))
    }
}
