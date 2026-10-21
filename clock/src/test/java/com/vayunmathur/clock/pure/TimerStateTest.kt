package com.vayunmathur.clock.pure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Pins the countdown-timer state machine: remaining-time math, pause /
 * resume / reset transitions and the finished-timer restart rule.
 *
 * Mirrors platform/ClockUiContract.timerRemaining and the notification
 * receivers' pause-resume-reset semantics with kotlin.time so the host
 * JVM can run it without any device or database.
 */
class TimerStateTest {
    private val base = Instant.fromEpochMilliseconds(1_000_000L)

    private fun remaining(
        isRunning: Boolean,
        length: Duration,
        start: Instant,
        now: Instant,
    ): Duration {
        val raw = if (isRunning) length - (now - start) else length
        return raw.coerceAtLeast(Duration.ZERO)
    }

    private fun addMinute(length: Duration): Duration = length + 60.seconds

    @Test
    fun pausedTimerIgnoresWallClock() {
        val later = base + 30.seconds
        assertEquals(5.minutes, remaining(false, 5.minutes, base, later))
    }

    @Test
    fun runningTimerSubtractsElapsed() {
        val now = base + 90.seconds
        assertEquals(30.seconds, remaining(true, 2.minutes, base, now))
    }

    @Test
    fun elapsedExactlyEqualsLengthGivesZero() {
        val now = base + 60.seconds
        assertEquals(Duration.ZERO, remaining(true, 60.seconds, base, now))
    }

    @Test
    fun elapsedBeyondLengthClampsToZero() {
        val now = base + 120.seconds
        assertEquals(Duration.ZERO, remaining(true, 60.seconds, base, now))
    }

    @Test
    fun zeroLengthStaysZeroWhenRunning() {
        val now = base + 5.seconds
        assertEquals(Duration.ZERO, remaining(true, Duration.ZERO, base, now))
    }

    @Test
    fun pauseFreezesRemainingAcrossLaterReads() {
        val pausedAt = base + 20.seconds
        val frozen = remaining(true, 60.seconds, base, pausedAt)
        assertEquals(40.seconds, frozen)
        assertEquals(frozen, remaining(false, frozen, base, pausedAt + 300.seconds))
    }

    @Test
    fun resumeRestartsClockFromSameRemaining() {
        val frozen = 40.seconds
        val resumedAt = base + 1_000.seconds
        assertEquals(30.seconds, remaining(true, frozen, resumedAt, resumedAt + 10.seconds))
    }

    @Test
    fun resetRestoresTotalLength() {
        val total = 10.minutes
        val partlyElapsed = remaining(true, 10.minutes, base, base + 9.minutes)
        assertEquals(1.minutes, partlyElapsed)
        assertEquals(total, remaining(false, total, base, base + 9.minutes))
    }

    @Test
    fun addMinuteAddsSixtySecondsWhenPaused() {
        assertEquals(6.minutes, addMinute(5.minutes))
    }

    @Test
    fun addMinuteShiftsFinishLaterWhenRunning() {
        val length = addMinute(5.minutes)
        assertEquals(5.minutes, remaining(true, length, base, base + 1.minutes))
    }

    @Test
    fun oneMillisecondBeforeFinishLeavesOneMillisecond() {
        val now = base + 59_999.milliseconds
        assertEquals(1.milliseconds, remaining(true, 60.seconds, base, now))
    }

    @Test
    fun futureStartTimeNeverShrinksBelowStoredLength() {
        val startInFuture = base + 10.seconds
        val shown = remaining(true, 60.seconds, startInFuture, base)
        assertTrue(shown >= 60.seconds, "lead time must not shrink the display")
    }

    @Test
    fun negativeStoredLengthCoercesToZero() {
        assertEquals(Duration.ZERO, remaining(false, (-5).seconds, base, base))
        assertEquals(Duration.ZERO, remaining(true, (-5).seconds, base, base + 1.seconds))
    }

    @Test
    fun largeDurationArithmeticStaysExact() {
        val now = base + 1.hours
        assertEquals(98.hours, remaining(true, 99.hours, base, now))
    }

    @Test
    fun emptyNameAndZeroTotalIsAlreadyFinished() {
        assertEquals(Duration.ZERO, remaining(false, Duration.ZERO, base, base))
        assertEquals(Duration.ZERO, remaining(true, Duration.ZERO, base, base + 1.hours))
    }
}
