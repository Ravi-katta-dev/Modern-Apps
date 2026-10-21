package com.vayunmathur.library.ui.odf

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Extends [MarkdownOdfConverterTest] with the normalization edges its round-trips imply but
 * never pin: alternate markers, renumbering of incoming lists, unclosed fences, mid-word
 * punctuation, and list interruption. Pure converter, plain host JVM.
 */
class MarkdownOdfEdgeTest {

    private fun roundTrip(md: String): String =
        MarkdownOdfConverter.odfToMarkdown(MarkdownOdfConverter.markdownToOdf(md))

    @Test
    fun hash_without_a_space_stays_literal_text() {
        assertEquals("#nospace", roundTrip("#nospace"))
    }

    @Test
    fun alternate_hr_markers_normalize_to_dashes() {
        assertEquals("---", roundTrip("***"))
        assertEquals("---", roundTrip("___"))
    }

    @Test
    fun paren_numbered_markers_normalize_to_dots() {
        assertEquals("1. one\n2. two", roundTrip("1) one\n2) two"))
    }

    @Test
    fun incoming_numbers_are_renumbered_from_one() {
        assertEquals("1. five\n2. six", roundTrip("5. five\n6. six"))
    }

    @Test
    fun nested_numbered_list_keeps_its_indent() {
        assertEquals("1. top\n  1. child", roundTrip("1. top\n  1. child"))
    }

    @Test
    fun unclosed_fence_closes_at_end_of_input() {
        assertEquals("```\nline\n```", roundTrip("```\nline"))
    }

    @Test
    fun blockquote_with_bold_round_trips() {
        assertEquals("> **bold** quote", roundTrip("> **bold** quote"))
    }

    @Test
    fun link_with_a_bold_label_round_trips() {
        assertEquals("[**b**](https://x.test)", roundTrip("[**b**](https://x.test)"))
    }

    @Test
    fun nested_checklist_round_trips() {
        assertEquals("  - [ ] child", roundTrip("  - [ ] child"))
    }

    @Test
    fun mid_word_underscores_stay_literal() {
        assertEquals("snake_case_name", roundTrip("snake_case_name"))
        assertEquals("file_v2_final", roundTrip("file_v2_final"))
    }

    @Test
    fun heading_with_strikethrough_round_trips() {
        assertEquals("# ~~gone~~", roundTrip("# ~~gone~~"))
    }

    @Test
    fun inline_code_protects_asterisks() {
        assertEquals("`a*b`", roundTrip("`a*b`"))
    }

    @Test
    fun ordered_list_interrupted_by_body_restarts() {
        assertEquals("1. a\n2. b\ntext\n1. c", roundTrip("1. a\n2. b\ntext\n1. c"))
    }
}
