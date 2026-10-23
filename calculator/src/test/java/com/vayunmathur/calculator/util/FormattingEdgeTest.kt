package com.vayunmathur.calculator.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormattingEdgeTest {

    private fun unit(token: String): UnitDef = UnitRegistry.parseTokens[token] ?: error("no unit '$token'")

    @Test
    fun nanAndInfinitiesHaveReadableForms() {
        assertEquals("Undefined", formatResult(Double.NaN))
        assertEquals("∞", formatResult(Double.POSITIVE_INFINITY))
        assertEquals("-∞", formatResult(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun zeroAndNegativeZeroShowAsZero() {
        assertEquals("0", formatResult(0.0))
        assertEquals("0", formatResult(-0.0))
    }

    @Test
    fun floatArtefactsAreRoundedAway() {
        assertEquals("0.3", formatResult(0.1 + 0.2))
        assertEquals("2.5", formatResult(2.50))
        assertEquals("0.333333333333", formatResult(1.0 / 3.0))
    }

    @Test
    fun largeMagnitudesUseScientificNotation() {
        assertEquals("1e+12", formatResult(1e12))
        assertTrue(formatResult(1e15).contains("e"))
        assertTrue(formatResult(-2.5e13).contains("e"))
    }

    @Test
    fun normalMagnitudesStayPlain() {
        assertEquals("999999999999", formatResult(999999999999.0))
        assertEquals("0.000001", formatResult(0.000001))
        assertEquals("-42.5", formatResult(-42.5))
        assertEquals("123456789", formatResult(123456789.0))
    }

    @Test
    fun smallMagnitudesUseScientificNotation() {
        assertTrue(formatResult(1e-7).contains("e"))
        assertTrue(formatResult(-1e-9).contains("e"))
    }

    @Test
    fun tokenFormUsesPlainSpaceForRoundTrips() {
        val q = Expression.parse("5m").evalQuantity()
        assertEquals("5 m", formatQuantity(q, unit("m"), useToken = true))
    }

    @Test
    fun displayFormUsesThinSpace() {
        val q = Expression.parse("5m").evalQuantity()
        val text = formatQuantity(q, unit("m"))
        assertEquals(3, text.length)
        assertEquals('5', text[0])
        assertEquals(0x202F, text[1].code)
        assertEquals('m', text[2])
    }

    @Test
    fun dimensionlessValueIgnoresUnit() {
        assertEquals("5", formatQuantity(Quantity.scalar(5.0), unit("m")))
        assertEquals("5", formatQuantity(Quantity.scalar(5.0), null))
    }

    @Test
    fun instantTokenFormIsEpoch() {
        assertEquals("#1000000", formatInstant(1000000.0, useToken = true))
        assertEquals("#-5", formatInstant(-5.0, useToken = true))
    }

    @Test
    fun renderInputReplacesEpochTokens() {
        val rendered = renderInputForDisplay("a #1000000 b")
        assertTrue(!rendered.contains("#"), "epoch token was not replaced: $rendered")
        assertTrue(rendered.startsWith("a ") && rendered.endsWith(" b"))
    }
}
