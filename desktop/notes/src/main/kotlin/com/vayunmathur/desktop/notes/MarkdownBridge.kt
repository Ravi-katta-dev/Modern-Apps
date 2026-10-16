package com.vayunmathur.desktop.notes

import com.vayunmathur.everysync.core.data.RemoteNote
import com.vayunmathur.everysync.core.notes.NoteBlock
import com.vayunmathur.everysync.core.notes.noteBodyText
import com.vayunmathur.everysync.core.notes.parseNoteBody

/**
 * JVM export of a note body to Markdown, mirroring Android
 * `NoteMarkdownExporter` minus the Android-only parts.
 *
 * - Text blocks emit verbatim (via the shared [noteBodyText] equivalent —
 *   the same plain-text projection used for search/previews/sharing).
 * - Image blocks emit a `![](fileName)` reference; bytes stay in the
 *   [BlobStore] keyed by `fileName` and are never inlined (no base64 data
 *   URIs here — the desktop shell has no downscaler yet).
 * - Ink blocks emit a one-line placeholder with the stroke count (no SVG
 *   rasterizer on the JVM yet).
 *
 * Export-only: the stored `blocksJson` stays the source of truth; this never
 * touches storage. No Android imports (`Bitmap`, `Context`, `FileProvider`).
 */
object MarkdownBridge {

    /** Full markdown document for [note]: title heading + body blocks. */
    fun exportNote(
        note: RemoteNote,
        imageMarkdown: (fileName: String) -> String = { fileName -> "![]($fileName)" },
    ): String {
        val body = parseNoteBody(note.blocksJson, note.content)
        val parts = body.blocks.map { block ->
            when (block) {
                is NoteBlock.Text -> block.markdown
                is NoteBlock.Image -> imageMarkdown(block.fileName)
                is NoteBlock.Ink -> inkPlaceholder(block)
            }
        }.filter { it.isNotBlank() }
        val title = note.title.ifBlank { "Untitled" }
        return (listOf("# $title") + parts).joinToString("\n\n").trim() + "\n"
    }

    /** Plain-text projection (search, previews, list rows). */
    fun plainText(note: RemoteNote): String {
        if (note.blocksJson.isNullOrBlank()) return note.content.trim()
        return noteBodyText(parseNoteBody(note.blocksJson, note.content))
    }

    private fun inkPlaceholder(block: NoteBlock.Ink): String {
        val strokes = block.strokes.size
        val points = block.strokes.sumOf { it.points.size }
        return "_[handwriting: $strokes stroke(s), $points point(s)]_"
    }
}
