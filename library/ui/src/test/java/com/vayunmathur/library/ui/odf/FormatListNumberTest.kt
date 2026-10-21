package com.vayunmathur.library.ui.odf

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [formatListNumber] and [listPrefixFor], the two pure helpers behind visible list
 * prefixes. Same-package tests reach them without any Android runtime; the Compose-typed
 * fields on [OdfParagraph] are plain data on the unit-test classpath, as [ListNumberingTest]
 * already relies on.
 */
class FormatListNumberTest {

    private fun para(
        level: Int = 1,
        type: ListType = ListType.BULLET,
        index: Int = 1,
        checked: Boolean = false,
        bullet: String = "•",
        format: String = "1",
        prefix: String = "",
        suffix: String = ".",
        style: ParagraphStyle = ParagraphStyle.LIST_ITEM,
        outline: String? = null,
    ) = OdfParagraph(
        spans = listOf(OdfSpan("x")),
        style = style,
        listLevel = level,
        listType = type,
        listItemIndex = index,
        listChecked = checked,
        listBulletChar = bullet,
        listNumberFormat = format,
        listNumberPrefix = prefix,
        listNumberSuffix = suffix,
        outlineNumber = outline,
    )

    @Test
    fun digits_and_unknown_formats_fall_back_to_digits() {
        assertEquals("5", formatListNumber(5, "1"))
        assertEquals("5", formatListNumber(5, "x"))
    }

    @Test
    fun zero_and_negative_indices_floor_at_one() {
        assertEquals("1", formatListNumber(0, "1"))
        assertEquals("a", formatListNumber(-3, "a"))
        assertEquals("i", formatListNumber(0, "i"))
    }

    @Test
    fun alpha_counts_like_a_spreadsheet_column() {
        assertEquals("a", formatListNumber(1, "a"))
        assertEquals("z", formatListNumber(26, "a"))
        assertEquals("aa", formatListNumber(27, "a"))
        assertEquals("ab", formatListNumber(28, "a"))
        assertEquals("AA", formatListNumber(27, "A"))
    }

    @Test
    fun roman_uses_subtractive_pairs() {
        assertEquals("iv", formatListNumber(4, "i"))
        assertEquals("ix", formatListNumber(9, "i"))
        assertEquals("xiv", formatListNumber(14, "i"))
        assertEquals("mmxxvi", formatListNumber(2026, "i"))
        assertEquals("IV", formatListNumber(4, "I"))
    }

    @Test
    fun roman_out_of_range_falls_back_to_digits() {
        assertEquals("4000", formatListNumber(4000, "i"))
    }

    @Test
    fun bullets_cycle_by_level() {
        assertEquals("•  ", listPrefixFor(para(level = 1)))
        assertEquals("    ◦  ", listPrefixFor(para(level = 2)))
        assertEquals("        ▪  ", listPrefixFor(para(level = 3)))
        assertEquals("            •  ", listPrefixFor(para(level = 4)))
    }

    @Test
    fun numbered_style_varies_by_level() {
        assertEquals("3.  ", listPrefixFor(para(type = ListType.NUMBERED, index = 3)))
        assertEquals("    b)  ", listPrefixFor(para(level = 2, type = ListType.NUMBERED, index = 2)))
    }

    @Test
    fun custom_number_affixes_are_honored() {
        val p = para(type = ListType.NUMBERED, index = 4, prefix = "(", suffix = ")")
        assertEquals("(4)  ", listPrefixFor(p))
    }

    @Test
    fun checkboxes_render_their_state() {
        assertEquals("☐  ", listPrefixFor(para(type = ListType.CHECKBOX, checked = false)))
        assertEquals("☑  ", listPrefixFor(para(type = ListType.CHECKBOX, checked = true)))
    }

    @Test
    fun unreadable_bullet_glyphs_fall_back_through_the_visible_path() {
        assertEquals("•  ", listPrefixFor(para(bullet = "")))
        assertEquals("•  ", listPrefixFor(para(bullet = "")))
    }

    @Test
    fun headings_show_outline_numbers_or_nothing() {
        val numbered = para(style = ParagraphStyle.HEADING1, outline = "1.2.")
        assertEquals("1.2. ", listPrefixFor(numbered))
        assertEquals("", listPrefixFor(para(style = ParagraphStyle.HEADING2)))
    }

    @Test
    fun body_paragraphs_have_no_prefix() {
        assertEquals("", listPrefixFor(para(style = ParagraphStyle.BODY, level = 0)))
    }
}
