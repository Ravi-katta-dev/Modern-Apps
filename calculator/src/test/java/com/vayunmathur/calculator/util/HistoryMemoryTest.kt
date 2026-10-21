package com.vayunmathur.calculator.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryMemoryTest {

    @Test
    fun evaluatePushesNewestFirst() {
        val tab = CalculatorTab()
        tab.updateInput("2+3")
        tab.evaluate()
        tab.updateInput("10-4")
        tab.evaluate()
        assertEquals(2, tab.history.size)
        assertEquals(HistoryEntry("10-4", "6"), tab.history[0])
        assertEquals(HistoryEntry("2+3", "5"), tab.history[1])
    }

    @Test
    fun blankInputEvaluatesToNothing() {
        val tab = CalculatorTab()
        tab.evaluate()
        assertTrue(tab.history.isEmpty())
        assertEquals(0.0, tab.lastAnswer)
    }

    @Test
    fun invalidInputLeavesHistoryAndInputAlone() {
        val tab = CalculatorTab()
        tab.updateInput("1+")
        tab.evaluate()
        assertTrue(tab.history.isEmpty())
        assertEquals("1+", tab.input)
    }

    @Test
    fun nanResultIsNotRecorded() {
        val tab = CalculatorTab()
        tab.updateInput("0/0")
        tab.evaluate()
        assertTrue(tab.history.isEmpty())
        assertEquals("0/0", tab.input)
    }

    @Test
    fun ansThreadsThroughEvaluations() {
        val tab = CalculatorTab()
        tab.updateInput("2+3")
        tab.evaluate()
        tab.updateInput("ans*2")
        tab.evaluate()
        assertEquals(10.0, tab.lastAnswer)
        assertEquals(HistoryEntry("ans*2", "10"), tab.history[0])
    }

    @Test
    fun memoryAddAndSubtractFoldPreview() {
        val tab = CalculatorTab()
        tab.updateInput("5")
        tab.memoryAdd()
        assertEquals(5.0, tab.memory)
        tab.updateInput("2")
        tab.memorySubtract()
        assertEquals(3.0, tab.memory)
        tab.memoryClear()
        assertEquals(0.0, tab.memory)
    }

    @Test
    fun memoryIgnoresInvalidInput() {
        val tab = CalculatorTab()
        tab.updateInput("1+")
        tab.memoryAdd()
        assertEquals(0.0, tab.memory)
        tab.clear()
        tab.memorySubtract()
        assertEquals(0.0, tab.memory)
    }

    @Test
    fun memoryRecallInsertsFormattedValue() {
        val tab = CalculatorTab()
        tab.updateInput("8")
        tab.memoryAdd()
        tab.clear()
        tab.memoryRecall()
        assertEquals("8", tab.input)
    }

    @Test
    fun useHistorySeedsInputFromResult() {
        val tab = CalculatorTab()
        tab.updateInput("2+3")
        tab.evaluate()
        tab.useHistory(tab.history[0])
        assertEquals("5", tab.input)
    }

    @Test
    fun insertInstantAppendsEpochToken() {
        val tab = CalculatorTab()
        tab.insertInstant(1000000L)
        assertEquals("#1000000", tab.input)
    }

    @Test
    fun backspaceAndClearEditInput() {
        val tab = CalculatorTab()
        tab.append("12")
        tab.backspace()
        assertEquals("1", tab.input)
        tab.clear()
        assertEquals("", tab.input)
        assertEquals("", tab.preview)
    }
}
