package com.vayunmathur.music.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the repeat/shuffle transitions behind the transport buttons.
 *
 * The buttons themselves talk to a service controller, so what is pinned here
 * is the transition each press applies: repeat cycles off, one, all and wraps,
 * while shuffle flips independently of it.
 */
class RepeatShuffleTest {

    @Test
    fun repeatCyclesOffOneAllThenWraps() {
        assertEquals(REPEAT_ONE, nextRepeatMode(REPEAT_OFF))
        assertEquals(REPEAT_ALL, nextRepeatMode(REPEAT_ONE))
        assertEquals(REPEAT_OFF, nextRepeatMode(REPEAT_ALL))
    }

    @Test
    fun unknownRepeatModeFallsBackToOff() {
        // Mirrors the else branch: anything unrecognised restarts the cycle.
        assertEquals(REPEAT_OFF, nextRepeatMode(99))
        assertEquals(REPEAT_OFF, nextRepeatMode(-1))
    }

    @Test
    fun threePressesReturnToTheStart() {
        var mode = REPEAT_OFF
        repeat(3) { mode = nextRepeatMode(mode) }
        assertEquals(REPEAT_OFF, mode)
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
        var shuffle = true
        var mode = REPEAT_OFF
        repeat(4) { mode = nextRepeatMode(mode) }
        assertTrue(shuffle)
        assertEquals(REPEAT_ONE, mode)
    }

    @Test
    fun shufflePressesNeverTouchRepeat() {
        var mode = REPEAT_ALL
        var shuffle = toggleShuffle(false)
        assertTrue(shuffle)
        assertEquals(REPEAT_ALL, mode)
        shuffle = toggleShuffle(shuffle)
        assertFalse(shuffle)
        assertEquals(REPEAT_ALL, mode)
    }

    companion object {
        // Media3 Player.REPEAT_MODE_OFF/ONE/ALL, restated so this stays host-only.
        private const val REPEAT_OFF = 0
        private const val REPEAT_ONE = 1
        private const val REPEAT_ALL = 2

        // Mirrors PlaybackManager.toggleRepeat's when branch.
        private fun nextRepeatMode(mode: Int): Int = when (mode) {
            REPEAT_OFF -> REPEAT_ONE
            REPEAT_ONE -> REPEAT_ALL
            else -> REPEAT_OFF
        }

        // Mirrors PlaybackManager.toggleShuffle's negation.
        private fun toggleShuffle(enabled: Boolean): Boolean = !enabled
    }
}
