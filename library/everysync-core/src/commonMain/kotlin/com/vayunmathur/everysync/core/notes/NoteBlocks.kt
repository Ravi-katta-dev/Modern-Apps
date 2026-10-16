package com.vayunmathur.everysync.core.notes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/**
 * One piece of a note's body. A note is an ordered list of blocks so it can mix
 * typed text, inserted images, and handwriting.
 *
 * Wire shape (preserved verbatim from Android):
 * `{"blocks":[{"type":"text","id":"…","markdown":"…"},
 * {"type":"image","id":"…","fileName":"<sha256>.jpg","widthFraction":1.0},
 * {"type":"ink","id":"…","strokes":[…],"heightDp":300}]}`
 * Image bytes ride the blob channel keyed by `fileName`; they are never inline.
 */
@Serializable
sealed interface NoteBlock {
    val id: String

    @Serializable
    @SerialName("text")
    data class Text(
        val markdown: String = "",
        override val id: String = randomBlockId(),
    ) : NoteBlock

    /** [fileName] names an image file in the note-images/blob store. */
    @Serializable
    @SerialName("image")
    data class Image(
        val fileName: String,
        /** Display width as a fraction of the editor width, so images can be resized. */
        val widthFraction: Float = 1f,
        override val id: String = randomBlockId(),
    ) : NoteBlock

    /** Handwriting strokes drawn on a canvas [heightDp] tall. */
    @Serializable
    @SerialName("ink")
    data class Ink(
        val strokes: List<InkStroke> = emptyList(),
        val heightDp: Int = 300,
        override val id: String = randomBlockId(),
    ) : NoteBlock
}

/** Platform-free ink stroke: polyline points + brush attributes. */
@Serializable
data class InkPoint(
    val x: Float,
    val y: Float,
    val timeMillis: Long = 0L,
    val pressure: Float = 1f,
    val tiltRadians: Float = 0f,
    val orientationRadians: Float = 0f,
)

@Serializable
data class InkStroke(
    val brushFamily: String = "pressurePen",
    val brushSize: Float = 4f,
    val brushColor: Int = -0x1000000,
    val brushEpsilon: Float = 0.1f,
    val points: List<InkPoint> = emptyList(),
)

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
fun randomBlockId(): String = Uuid.random().toString()

/**
 * The full body of a note: an ordered list of inline [blocks].
 */
@Serializable
data class NoteBody(
    val blocks: List<NoteBlock> = emptyList(),
)

// ignoreUnknownKeys drops legacy fields (e.g. an old "floating" list) so older notes still parse.
private val blockJson = Json { ignoreUnknownKeys = true }

/** Parses a stored blocks JSON string, or falls back to a single text block. */
fun parseNoteBody(blocksJson: String?, fallbackContent: String = ""): NoteBody {
    if (blocksJson.isNullOrBlank()) return NoteBody(blocks = listOf(NoteBlock.Text(fallbackContent)))
    return try {
        blockJson.decodeFromString<NoteBody>(blocksJson)
    } catch (_: SerializationException) {
        NoteBody(blocks = listOf(NoteBlock.Text(fallbackContent)))
    }
}

/** Serializes [body] to the stored blocks JSON string. */
fun serializeNoteBody(body: NoteBody): String = blockJson.encodeToString(body)

/** Plain-text projection of [body] for search, previews, sharing, and intents. */
fun noteBodyText(body: NoteBody): String =
    body.blocks.filterIsInstance<NoteBlock.Text>()
        .joinToString("\n\n") { it.markdown }
        .trim()
