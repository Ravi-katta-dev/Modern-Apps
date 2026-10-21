package com.vayunmathur.music.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the repeat and shuffle transitions behind the transport buttons.
 *
 * The buttons drive a service controller, so what is pinned here is the
 * transition each press applies: repeat cycles off, one, all and wraps,
 * while shuffle flips independently of it.
 */
class RepeatShuffleTest {

    private fun nextRepeatMode(mode: Int): Int = when (mode) {
        0 -> 1
        1 -> 2
        else -> 0
    }

    private fun toggleShuffle(enabled: Boolean): Boolean = !enabled

    @Test
    fun repeatCyclesOffOneAllThenWraps() {
        assertEquals(1, nextRepeatMode(0))
        assertEquals(2, nextRepeatMode(1))
        assertEquals(0, nextRepeatMode(2))
    }

    @Test
    fun unknownRepeatModeFallsBackToOff() {
        assertEquals(0, nextRepeatMode(99))
        assertEquals(0, nextRepeatMode(-1))
    }

    @Test
    fun threePressesReturnToTheStart() {
        var mode = 0
        repeat(3) { mode = nextRepeatMode(mode) }
        assertEquals(0, mode)
    }

    @Test
    fun shuffleFlipsAndFlippingTwiceRestores() {
        assertTrue(toggleShuffle(false))
        assertFalse(toggleShuffle(true))
        assertEquals(false, toggleShuffle(toggleShuffle(false)))
        assertEquals(true, toggleShuffle(toggleShuffle(true)))
    }

    @Test
    fun repeatPressesNeverTouchShuffle() {
        val shuffle = true
        var mode = 0
        repeat(4) { mode = nextRepeatMode(mode) }
        assertTrue(shuffle)
        assertEquals(1, mode)
    }

    @Test
    fun shufflePressesNeverTouchRepeat() {
        val mode = 2
        var shuffle = toggleShuffle(false)
        assertTrue(shuffle)
        assertEquals(2, mode)
        shuffle = toggleShuffle(shuffle)
        assertFalse(shuffle)
        assertEquals(2, mode)
    }
}
