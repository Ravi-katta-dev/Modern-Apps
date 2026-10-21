package com.vayunmathur.music.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the duration text the list rows show.
 *
 * Mirrors [formatDuration] in `Util.kt`, which cannot be imported here because
 * that file also owns thumbnails and retrievers. The rules pinned are the ones
 * a reader checks: seconds pad, minutes do not, hours appear only when needed.
 */
class DurationFormatTest {

    @Test
    fun zeroAndSubSecondReadAsZero() {
        assertEquals("0:00", format(0))
        assertEquals("0:00", format(999))
    }

    @Test
    fun secondsPadButMinutesDoNot() {
        assertEquals("0:05", format(5_000))
        assertEquals("3:07", format(187_000))
        assertEquals("10:00", format(600_000))
    }

    @Test
    fun truncationDropsMillisRatherThanRounding() {
        assertEquals("3:07", format(187_999))
    }

    @Test
    fun hoursAppearOnlyPastOneHour() {
        assertEquals("59:59", format(3_599_999))
        assertEquals("1:00:00", format(3_600_000))
        assertEquals("1:02:03", format(3_723_000))
        assertEquals("10:00:00", format(36_000_000))
    }

    @Test
    fun minutesWrapInsideTheHour() {
        assertEquals("1:01:01", format(3_661_000))
        assertEquals("2:00:00", format(7_200_000))
    }

    @Test
    fun negativeDurationsClampThroughIntegerDivision() {
        // No guard in the source: a negative value formats by the same division.
        assertEquals("0:00", format(-1))
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1000L
        private const val SECONDS_PER_MINUTE = 60L
        private const val SECONDS_PER_HOUR = 3600L

        // Mirrors Util.formatDuration without the Android file around it.
        private fun format(durationMs: Long): String {
            val totalSeconds = durationMs / MILLIS_PER_SECOND
            val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
            val seconds = totalSeconds % SECONDS_PER_MINUTE
            val hours = totalSeconds / SECONDS_PER_HOUR
            return if (hours > 0) {
                "%d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "%d:%02d".format(minutes, seconds)
            }
        }
    }
}
