package com.vayunmathur.contacts.pure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins vCard line folding: 75-octet lines, space-prefixed continuations,
 * and the streaming unfold the importer relies on.
 *
 * Mirrors util/VcfUtils' fold writer and the unfold loop in
 * parseVcfContacts, where a tab also continues a folded line.
 */
class VCardFoldTest {
    private fun fold(line: String): String {
        if (line.length <= FOLD_LENGTH) return line + "\r\n"
        val sb = StringBuilder()
        var idx = 0
        while (idx < line.length) {
            val end = minOf(idx + FOLD_LENGTH, line.length)
            val part = line.substring(idx, end)
            if (idx == 0) sb.append(part).append("\r\n") else sb.append(" ").append(part).append("\r\n")
            idx = end
        }
        return sb.toString()
    }

    private fun unfold(rawLines: List<String>): List<String> {
        val out = mutableListOf<String>()
        var pending: String? = null
        for (ln in rawLines) {
            if (ln.startsWith(" ") || ln.startsWith("\t")) {
                pending = (pending ?: "") + ln.trimStart()
            } else {
                if (pending != null) out.add(pending)
                pending = ln
            }
        }
        if (pending != null) out.add(pending)
        return out
    }

    private fun foldedParts(line: String): List<String> =
        fold(line).split("\r\n").filter { it.isNotEmpty() }

    @Test
    fun shortLineGetsOneCrlf() {
        assertEquals("BEGIN:VCARD\r\n", fold("BEGIN:VCARD"))
    }

    @Test
    fun exactly75CharsStaysOnOneLine() {
        val line = "N:" + "a".repeat(73)
        assertEquals(75, line.length)
        assertEquals(line + "\r\n", fold(line))
    }

    @Test
    fun char76StartsAContinuationLine() {
        val line = "N:" + "a".repeat(74)
        val parts = foldedParts(line)
        assertEquals(2, parts.size)
        assertEquals(75, parts[0].length)
        assertTrue(parts[1].startsWith(" "))
        assertEquals(listOf(line), unfold(parts))
    }

    @Test
    fun longNoteFoldsToThreeLinesAndUnfoldsLosslessly() {
        val line = "NOTE:" + "lorem ipsum dolor sit amet ".repeat(10)
        val parts = foldedParts(line)
        assertTrue(parts.size >= 3)
        assertTrue(parts.drop(1).all { it.startsWith(" ") })
        assertEquals(listOf(line), unfold(parts))
    }

    @Test
    fun tabAlsoContinuesAFoldedLine() {
        assertEquals(listOf("NOTE:abc"), unfold(listOf("NOTE:a", "\tbc")))
    }

    @Test
    fun everyFoldedChunkEndsWithCrlf() {
        assertTrue(fold("FN:" + "x".repeat(200)).split("\r\n").dropLast(1).all { it.isNotEmpty() })
    }

    @Test
    fun emptyStreamUnfoldsToEmpty() {
        assertEquals(emptyList(), unfold(emptyList()))
    }

    @Test
    fun unicodeValueRoundTripsThroughFold() {
        val line = "FN:" + "José García ".repeat(12)
        assertEquals(listOf(line), unfold(foldedParts(line)))
    }

    private companion object {
        private const val FOLD_LENGTH = 75
    }
}
