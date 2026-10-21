package com.vayunmathur.openassistant.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins embedding-input chunking for the SigLIP2 text tower.
 *
 * Long passages are split into word-bounded chunks that each fit the fixed
 * context length, so nothing is silently dropped and no chunk overruns the
 * model. Mirrors the split/pad/truncate contract without ONNX or files.
 */
class EmbeddingChunkTest {

    private fun chunkWords(text: String, maxWords: Int): List<String> {
        require(maxWords > 0)
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        var i = 0
        while (i < words.size) {
            val end = minOf(i + maxWords, words.size)
            out.add(words.subList(i, end).joinToString(" "))
            i = end
        }
        return out
    }

    private fun padToLength(ids: List<Int>, seqLen: Int, padId: Int): List<Int> {
        require(seqLen >= 0)
        if (ids.size >= seqLen) return ids.take(seqLen)
        return ids + List(seqLen - ids.size) { padId }
    }

    @Test
    fun emptyAndBlankInputsYieldNoChunks() {
        assertEquals(emptyList(), chunkWords("", 64))
        assertEquals(emptyList(), chunkWords("   \n\t ", 64))
    }

    @Test
    fun shortInputIsOneChunk() {
        assertEquals(listOf("hello world"), chunkWords("hello world", 64))
        assertEquals(listOf("hello world"), chunkWords("  hello   world ", 64))
    }

    @Test
    fun exactFitDoesNotSplit() {
        val text = (1..64).joinToString(" ") { "w$it" }
        assertEquals(listOf(text), chunkWords(text, 64))
    }

    @Test
    fun oversizedInputSplitsOnWordBoundaries() {
        val text = (1..130).joinToString(" ") { "w$it" }
        val chunks = chunkWords(text, 64)
        assertEquals(3, chunks.size)
        assertEquals(64, chunks[0].split(" ").size)
        assertEquals(64, chunks[1].split(" ").size)
        assertEquals(2, chunks[2].split(" ").size)
        val rejoined = chunks.joinToString(" ")
        assertEquals(text, rejoined)
    }

    @Test
    fun singleWordChunksNeverMerge() {
        val chunks = chunkWords("a b c", 1)
        assertEquals(listOf("a", "b", "c"), chunks)
    }

    @Test
    fun paddingFillsShortSequences() {
        assertEquals(listOf(1, 2, 0, 0), padToLength(listOf(1, 2), 4, 0))
        assertEquals(emptyList(), padToLength(emptyList(), 0, 0))
        assertEquals(listOf(0, 0, 0), padToLength(emptyList(), 3, 0))
    }

    @Test
    fun truncationKeepsTheHead() {
        val ids = (1..100).toList()
        val out = padToLength(ids, 64, 0)
        assertEquals(64, out.size)
        assertEquals((1..64).toList(), out)
        assertTrue(out.all { it != 0 })
    }
}
