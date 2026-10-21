package com.vayunmathur.openassistant.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins conversation history handling for the chat inference loop.
 *
 * The service rebuilds the engine conversation from stored messages and
 * drops the just-sent echo unless it is older than the recency cutoff, so
 * a duplicate row never seeds the model twice. These helpers mirror that
 * contract without Room, Context, or inference dependencies.
 */
class ConversationTurnTest {

    private data class Turn(val text: String, val timestamp: Long)

    private fun filterEcho(
        history: List<Turn>,
        userText: String,
        now: Long,
        recencyMs: Long,
    ): List<Turn> {
        val cutoff = now - recencyMs
        return history.filter { it.text != userText || it.timestamp < cutoff }
    }

    private fun takeRecent(history: List<Turn>, maxTurns: Int): List<Turn> {
        if (maxTurns <= 0) return emptyList()
        return history.takeLast(maxTurns)
    }

    @Test
    fun emptyHistoryStaysEmpty() {
        assertEquals(emptyList(), filterEcho(emptyList(), "hi", 1_000L, 1_000L))
        assertEquals(emptyList(), takeRecent(emptyList(), 10))
    }

    @Test
    fun freshEchoOfCurrentTextIsDropped() {
        val history = listOf(Turn("hi", 900L), Turn("hello", 950L))
        val expected = listOf(Turn("hi", 900L))
        assertEquals(expected, filterEcho(history, "hello", 1_000L, 1_000L))
    }

    @Test
    fun oldDuplicateSurvivesTheCutoff() {
        val history = listOf(Turn("hello", 100L), Turn("other", 900L))
        assertEquals(history, filterEcho(history, "hello", 10_000L, 1_000L))
    }

    @Test
    fun cutoffBoundaryKeepsStrictlyOlderRows() {
        val now = 2_000L
        val cutoff = now - 1_000L
        val history = listOf(Turn("x", cutoff), Turn("x", cutoff - 1))
        val expected = listOf(Turn("x", cutoff - 1))
        assertEquals(expected, filterEcho(history, "x", now, 1_000L))
    }

    @Test
    fun trimKeepsNewestAndPreservesOrder() {
        val history = (1..5).map { Turn("m$it", it.toLong()) }
        val expected = listOf(Turn("m4", 4L), Turn("m5", 5L))
        assertEquals(expected, takeRecent(history, 2))
    }

    @Test
    fun oversizedHistoryTrimsToBudget() {
        val history = (1..10_000).map { Turn("m$it", it.toLong()) }
        val kept = takeRecent(history, 50)
        assertEquals(50, kept.size)
        assertEquals("m9951", kept.first().text)
        assertEquals("m10000", kept.last().text)
    }

    @Test
    fun nonPositiveBudgetKeepsNothing() {
        val history = listOf(Turn("a", 1L))
        assertTrue(takeRecent(history, 0).isEmpty())
        assertTrue(takeRecent(history, -3).isEmpty())
    }

    @Test
    fun unlikeTextIsNeverFiltered() {
        val history = listOf(Turn("a", 1L), Turn("b", 2L))
        assertEquals(history, filterEcho(history, "zzz", 3L, 1_000L))
    }
}
