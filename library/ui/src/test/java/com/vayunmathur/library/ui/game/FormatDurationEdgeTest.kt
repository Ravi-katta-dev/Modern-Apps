package com.vayunmathur.library.ui.game

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Extends [FormatDurationTest] with the rollover boundaries it leaves out: minute/hour
 * carries, the full-day mark, and negative clamping past a single second.
 */
class FormatDurationEdgeTest {

    @Test
    fun minute_boundaries() {
        assertEquals("00:59", formatDuration(59))
        assertEquals("01:00", formatDuration(60))
        assertEquals("01:01", formatDuration(61))
        assertEquals("09:59", formatDuration(599))
        assertEquals("10:00", formatDuration(600))
    }

    @Test
    fun hour_boundary_carries_seconds() {
        assertEquals("1:00:01", formatDuration(3601))
        assertEquals("1:01:00", formatDuration(3660))
        assertEquals("1:01:01", formatDuration(3661))
    }

    @Test
    fun full_day_and_beyond() {
        assertEquals("23:59:59", formatDuration(86_399))
        assertEquals("24:00:00", formatDuration(86_400))
        assertEquals("25:01:01", formatDuration(90_061))
    }

    @Test
    fun zero_padding_holds_for_single_digits() {
        assertEquals("00:05", formatDuration(5))
        assertEquals("01:05", formatDuration(65))
        assertEquals("1:00:05", formatDuration(3605))
    }

    @Test
    fun larger_negatives_still_clamp_to_zero() {
        assertEquals("00:00", formatDuration(-60))
        assertEquals("00:00", formatDuration(-3600))
    }
}
