package com.vayunmathur.openassistant.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins settings and prompt-template logic for the chat system prompt.
 *
 * The service falls back to the built-in default when the stored prompt is
 * missing or blank, and composes screen-context turns without leaking empty
 * scaffolding. Mirrors that selection without DataStore or services.
 */
class PromptTemplateTest {

    private val defaultPrompt = "You are a helpful assistant.\nUse tools only when asked."

    private fun effectivePrompt(stored: String?): String {
        return if (stored.isNullOrBlank()) defaultPrompt else stored
    }

    private fun withScreenContext(screenContext: String?, userText: String): String {
        if (screenContext.isNullOrBlank()) return userText
        return "Current screen contents:\n$screenContext\n\nUser request: $userText"
    }

    private fun isPromptUsable(prompt: String, maxChars: Int = 8_000): Boolean {
        if (prompt.isBlank()) return false
        if (prompt.length > maxChars) return false
        return true
    }

    @Test
    fun nullAndBlankStoredPromptsFallBackToDefault() {
        assertEquals(defaultPrompt, effectivePrompt(null))
        assertEquals(defaultPrompt, effectivePrompt(""))
        assertEquals(defaultPrompt, effectivePrompt("   \n "))
    }

    @Test
    fun customPromptIsKeptVerbatim() {
        val custom = "Be terse.  Never call tools. "
        assertEquals(custom, effectivePrompt(custom))
    }

    @Test
    fun defaultPromptMentionsToolRestraint() {
        assertTrue(defaultPrompt.contains("tools"))
        assertFalse(defaultPrompt.isBlank())
    }

    @Test
    fun missingScreenContextLeavesTextAlone() {
        assertEquals("hi", withScreenContext(null, "hi"))
        assertEquals("hi", withScreenContext("", "hi"))
        assertEquals("hi", withScreenContext("  ", "hi"))
    }

    @Test
    fun screenContextWrapsUserRequest() {
        val out = withScreenContext("Inbox (2 unread)", "summarize")
        assertTrue(out.startsWith("Current screen contents:\n"))
        assertTrue(out.contains("Inbox (2 unread)"))
        assertTrue(out.endsWith("User request: summarize"))
    }

    @Test
    fun oversizedPromptIsNotUsable() {
        assertTrue(isPromptUsable("a"))
        assertFalse(isPromptUsable(""))
        assertFalse(isPromptUsable("   "))
        assertFalse(isPromptUsable("x".repeat(8_001)))
        assertTrue(isPromptUsable("x".repeat(8_000)))
    }

    @Test
    fun emptyUserTextStillCarriesContext() {
        val out = withScreenContext("ctx", "")
        assertTrue(out.contains("ctx"))
        assertTrue(out.contains("User request: "))
    }
}
