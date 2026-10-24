package com.vayunmathur.globalesim.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuoteMathTest {
    @Test
    fun formatsCents() {
        assertEquals("$5.00", QuoteMath.formatCents(500))
        assertEquals("$0.99", QuoteMath.formatCents(99))
        assertEquals("$20.00", QuoteMath.formatCents(2000))
        assertEquals("$0.00", QuoteMath.formatCents(0))
    }

    @Test
    fun parsesAmountsIncludingDecimalComma() {
        assertEquals(1200L, QuoteMath.parseAmountCents("12"))
        assertEquals(1250L, QuoteMath.parseAmountCents("12.5"))
        assertEquals(1250L, QuoteMath.parseAmountCents("$12.50"))
        assertEquals(1234L, QuoteMath.parseAmountCents("12,34"))
        assertEquals(500L, QuoteMath.parseAmountCents(" 5 "))
        assertNull(QuoteMath.parseAmountCents("12.345"))
        assertNull(QuoteMath.parseAmountCents("abc"))
        assertNull(QuoteMath.parseAmountCents(""))
        assertNull(QuoteMath.parseAmountCents("1e3"))
    }
}
