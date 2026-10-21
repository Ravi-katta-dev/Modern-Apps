package com.vayunmathur.openassistant.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins message formatting and truncation for chat bubbles and titles.
 *
 * The UI renders streamed markdown into bounded bubbles and derives the
 * conversation title from the first user turn, so overlong, blank, or
 * unicode-heavy text must degrade to a short single line rather than
 * breaking layout. Mirrors that contract without Compose or resources.
 */
class MessageFormatTest {

    private fun truncate(text: String, maxChars: Int): String {
        require(maxChars >= 0)
        if (text.length <= maxChars) return text
        if (maxChars <= 1) return text.take(maxChars)
        return text.take(maxChars - 1) + "\u2026"
    }

    private fun singleLine(text: String): String {
        return text.replace(Regex("\\s+"), " ").trim()
    }

    private fun titleFor(firstUserText: String, maxLen: Int = 48): String {
        val line = singleLine(firstUserText)
        if (line.isEmpty()) return "New conversation"
        return truncate(line, maxLen)
    }

    private fun canonicalRole(role: String): String {
        return when (role.lowercase()) {
            "user", "human" -> "user"
            "assistant", "model", "ai" -> "assistant"
            else -> "user"
        }
    }

    @Test
    fun emptyInputFallsBackToPlaceholderTitle() {
        assertEquals("New conversation", titleFor(""))
        assertEquals("New conversation", titleFor("   \n\t  "))
    }

    @Test
    fun shortTextIsKeptVerbatim() {
        assertEquals("Buy milk", titleFor("Buy milk"))
        assertEquals("hi", truncate("hi", 48))
    }

    @Test
    fun oversizedTitleTruncatesWithEllipsis() {
        val long = "a".repeat(200)
        val titled = titleFor(long)
        assertEquals(48, titled.length)
        assertTrue(titled.endsWith("\u2026"))
    }

    @Test
    fun newlinesAndTabsCollapseToSingleSpaces() {
        assertEquals("a b c", singleLine("a\n\nb\tc"))
        assertEquals("hello world", titleFor("  hello\nworld  "))
    }

    @Test
    fun truncateBoundaryKeepsExactFit() {
        assertEquals("abc", truncate("abc", 3))
        assertEquals("ab\u2026", truncate("abcd", 3))
    }

    @Test
    fun tinyBudgetsNeverEmitTheEllipsisAlone() {
        assertEquals("", truncate("hello", 0))
        assertEquals("h", truncate("hello", 1))
    }

    @Test
    fun unicodeTextTruncatesByCharsNotBytes() {
        val text = "h\u00e9llo w\u00f6rld"
        assertEquals(11, text.length)
        assertTrue(truncate(text, 5).length <= 5)
    }

    @Test
    fun unknownRolesFallBackToUser() {
        assertEquals("user", canonicalRole("user"))
        assertEquals("assistant", canonicalRole("model"))
        assertEquals("assistant", canonicalRole("AI"))
        assertEquals("user", canonicalRole("system"))
        assertEquals("user", canonicalRole(""))
        assertEquals("user", canonicalRole("??"))
    }
}
