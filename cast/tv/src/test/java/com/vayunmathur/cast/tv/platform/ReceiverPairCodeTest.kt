package com.vayunmathur.cast.tv.platform

import com.vayunmathur.cast.protocol.PairCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The six digits the TV puts on screen, apart from any TV framework.
 *
 * Six digits are enough only because the proof is transcript-bound and the receiver limits
 * attempts, so the shape of the code and the attempt boundary are pinned here.
 */
class ReceiverPairCodeTest {

    @Test
    fun `the code is six digits`() {
        assertEquals(6, PairCode.DIGITS)
    }

    @Test
    fun `three wrong attempts retire the code on screen`() {
        assertEquals(3, PairCode.MAX_ATTEMPTS)
    }

    @Test
    fun `six digits pass, including all zeros`() {
        assertTrue(PairCode.isWellFormed("123456"))
        assertTrue(PairCode.isWellFormed("000000"))
    }

    @Test
    fun `anything but six digits is refused without an HMAC`() {
        assertFalse(PairCode.isWellFormed("12345"))
        assertFalse(PairCode.isWellFormed("1234567"))
        assertFalse(PairCode.isWellFormed(""))
    }

    @Test
    fun `non-digits are refused, even at the right length`() {
        assertFalse(PairCode.isWellFormed("12a456"))
        assertFalse(PairCode.isWellFormed(" 12345"))
        assertFalse(PairCode.isWellFormed("12345 "))
    }

    @Test
    fun `generated codes are six well-formed digits`() {
        repeat(25) {
            val code = PairCode.random()
            assertEquals(PairCode.DIGITS, code.length)
            assertTrue(PairCode.isWellFormed(code), "$code is not well formed")
        }
    }
}
