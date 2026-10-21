package com.vayunmathur.clock.pure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Pins the timer and stopwatch display strings: the countdown card's
 * H:MM:SS versus M:SS shape and the stopwatch notification's MM:SS shape.
 *
 * Mirrors ui/components/TimerCard.formatTimerDuration and
 * platform/StopwatchNotificationHelper's elapsed formatter with pure
 * kotlin.time math so the host JVM can run it.
 */
class TimerDisplayFormatTest {
    private fun formatTimer(total: Duration): String =
        total.toComponents { hours, mins, secs, _ ->
            if (hours > 0) {
                "%d:%02d:%02d".format(hours, mins, secs)
            } else {
                "%d:%02d".format(mins, secs)
            }
        }

    private fun formatElapsed(totalMs: Long): String {
        val minutes = totalMs / MILLIS_PER_MINUTE
        val seconds = (totalMs % MILLIS_PER_MINUTE) / MILLIS_PER_SECOND
        return "%02d:%02d".format(minutes, seconds)
    }

    @Test
    fun zeroTimerShowsZeroMinutes() {
        assertEquals("0:00", formatTimer(Duration.ZERO))
    }

    @Test
    fun subMinuteTimerShowsSecondsOnly() {
        assertEquals("0:45", formatTimer(45.seconds))
    }

    @Test
    fun minuteBoundaryHasNoHours() {
        assertEquals("5:00", formatTimer(5.minutes))
        assertEquals("59:59", formatTimer(59.minutes + 59.seconds))
    }

    @Test
    fun hourBoundarySwitchesToHourShape() {
        assertEquals("1:00:00", formatTimer(60.minutes))
    }

    @Test
    fun multiHourTimerPadsMinutesAndSeconds() {
        assertEquals("2:05:09", formatTimer(125.minutes + 9.seconds))
    }

    @Test
    fun elapsedStartsAtZero() {
        assertEquals("00:00", formatElapsed(0L))
    }

    @Test
    fun elapsedPadsSingleDigits() {
        assertEquals("01:05", formatElapsed(65_000L))
    }

    @Test
    fun elapsedTruncatesSubSecondRemainder() {
        assertEquals("00:59", formatElapsed(59_999L))
    }

    @Test
    fun elapsedMinutesGrowPastOneHour() {
        assertEquals("60:00", formatElapsed(3_600_000L))
        assertEquals("90:00", formatElapsed(5_400_000L))
    }

    @Test
    fun elapsedHalfPastOneMinute() {
        assertEquals("01:30", formatElapsed(90_000L))
    }

    private companion object {
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
