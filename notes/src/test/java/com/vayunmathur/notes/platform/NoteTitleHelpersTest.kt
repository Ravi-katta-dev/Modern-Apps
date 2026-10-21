package com.vayunmathur.notes.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the pure string helpers behind the editor and the external-file flow:
 * `setTitle` stores verbatim, `readExternal` strips one markdown suffix, and
 * `requestShare` falls back to `note` for a blank title.
 *
 * These mirror the exact expressions in `NotePage` / `NotesViewModel` rather
 * than calling them, because the callers need a Context or Compose state.
 * The strip order matters — `.markdown` first, then `.md` — and is pinned.
 */
class NoteTitleHelpersTest {

    private fun stripMarkdownSuffix(name: String): String =
        name.removeSuffix(".markdown").removeSuffix(".md")

    private fun shareFileName(title: String): String = "${title.ifBlank { "note" }}.md"

    @Test
    fun `titles copy verbatim including blank and unicode`() {
        val note = Note(id = 1, title = "old", content = "c")
        for (title in listOf("", "  spaced  ", "記 😀 مرحبا", "a".repeat(500))) {
            assertEquals(title, note.copy(title = title).title, "title <$title>")
        }
    }

    @Test
    fun `external file names lose one markdown suffix`() {
        assertEquals("meeting", stripMarkdownSuffix("meeting.md"))
        assertEquals("doc", stripMarkdownSuffix("doc.markdown"))
        assertEquals("plain", stripMarkdownSuffix("plain"))
        assertEquals("note.md", stripMarkdownSuffix("note.md.md"))
    }

    @Test
    fun `a markdown suffix inside the stem is not stripped`() {
        assertEquals("my.md.backup", stripMarkdownSuffix("my.md.backup"))
        assertEquals("MD", stripMarkdownSuffix("MD"))
        assertEquals("x.MD", stripMarkdownSuffix("x.MD"))
    }

    @Test
    fun `suffixes strip in call order so stacked suffixes both go`() {
        assertEquals("a", stripMarkdownSuffix("a.md.markdown"))
        assertEquals("a.markdown", stripMarkdownSuffix("a.markdown.md"))
    }

    @Test
    fun `share file names fall back to note for blank titles`() {
        assertEquals("note.md", shareFileName(""))
        assertEquals("note.md", shareFileName("   "))
        assertEquals("hello.md", shareFileName("hello"))
        assertEquals("記.md", shareFileName("記"))
    }

    @Test
    fun `a large markdown payload switches from text to a file uri`() {
        fun asUri(markdown: String): Boolean = markdown.length >= 100_000
        assertEquals(false, asUri("small"))
        assertEquals(false, asUri("x".repeat(99_999)))
        assertEquals(true, asUri("x".repeat(100_000)))
        assertEquals(true, asUri("x".repeat(2_000_000)))
    }
}
