package com.vayunmathur.library.ui.odf

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Extends [RemapCaretTest] with the destructive and boundary edges: full deletion,
 * empty documents, whole-text replacement, multi-character deletions, and caret zero.
 * Pure string math on the plain host JVM.
 */
class RemapCaretEdgeTest {

    @Test
    fun deleting_everything_lands_at_zero() {
        assertEquals(0, remapCaret("abc", "", 3))
    }

    @Test
    fun inserting_into_an_empty_document_keeps_zero() {
        assertEquals(0, remapCaret("", "abc", 0))
    }

    @Test
    fun caret_at_the_change_start_stays_put() {
        assertEquals(1, remapCaret("ab", "aXb", 1))
        assertEquals(1, remapCaret("aXb", "ab", 1))
    }

    @Test
    fun whole_text_replacement_sends_a_mid_caret_to_zero() {
        assertEquals(0, remapCaret("abc", "XYZ", 2))
    }

    @Test
    fun caret_tracks_its_character_across_a_multiline_insert() {
        assertEquals(5, remapCaret("a\nb", "a\nX\nb", 3))
    }

    @Test
    fun multi_char_delete_before_caret_pulls_it_back() {
        assertEquals(1, remapCaret("aXYZb", "ab", 4))
    }

    @Test
    fun caret_zero_never_moves() {
        assertEquals(0, remapCaret("abc", "XYZabc", 0))
    }

    @Test
    fun append_after_the_caret_leaves_it() {
        assertEquals(1, remapCaret("ab", "abXY", 1))
        assertEquals(2, remapCaret("ab", "abXY", 2))
    }
}
