package com.vayunmathur.globalesim.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class QuoteMathTest {
    @Test
    fun formatsCents() {
        assertEquals("$5.00", QuoteMath.formatCents(500))
        assertEquals("$0.99", QuoteMath.formatCents(99))
        assertEquals("$20.00", QuoteMath.formatCents(2000))
        assertEquals("$0.00", QuoteMath.formatCents(0))
    }

    @Test
    fun mbForFloorsNeverOverpromises() {
        // $1 wallet at $1.38/GB ≈ 742 MB, floored.
        assertEquals(742L, QuoteMath.mbFor(138, 100))
        assertEquals(1024L, QuoteMath.mbFor(100, 100))
        assertEquals(0L, QuoteMath.mbFor(0, 100))
        assertEquals(0L, QuoteMath.mbFor(138, 0))
    }
}
