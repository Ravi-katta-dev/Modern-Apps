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
 * Pins the stopwatch state machine: live counting, pause accumulation,
 * reset clearing and cumulative-lap splits.
 *
 * Mirrors the ViewModel's stopwatchCountingTime combine plus the
 * notification receiver's toggle/lap persistence rules, reduced to pure
 * kotlin.time functions over (running, total, start, now).
 */
class StopwatchStateTest {
    private val base = Instant.fromEpochMilliseconds(2_000_000L)

    private fun counting(running: Boolean, total: Duration, start: Instant, now: Instant): Duration =
        if (running) total + (now - start) else total

    private fun pauseTotal(total: Duration, start: Instant, now: Instant): Duration =
        total + (now - start)

    private fun lapIfRunning(
        running: Boolean,
        total: Duration,
        start: Instant,
        now: Instant,
        laps: List<Duration>,
    ): List<Duration> = if (!running) laps else laps + counting(running, total, start, now)

    private fun splits(laps: List<Duration>): List<Duration> {
        val out = mutableListOf<Duration>()
        var previous = Duration.ZERO
        for (lap in laps) {
            out += lap - previous
            previous = lap
        }
        return out
    }

    @Test
    fun stoppedStopwatchShowsStoredTotal() {
        assertEquals(90.seconds, counting(false, 90.seconds, base, base + 60.seconds))
    }

    @Test
    fun runningStopwatchAddsLiveDelta() {
        assertEquals(100.seconds, counting(true, 90.seconds, base, base + 10.seconds))
    }

    @Test
    fun pauseAccumulatesElapsedIntoTotal() {
        assertEquals(15.seconds, pauseTotal(10.seconds, base, base + 5.seconds))
    }

    @Test
    fun resumeContinuesFromPausedTotal() {
        val paused = pauseTotal(10.seconds, base, base + 5.seconds)
        val resumedStart = base + 1_000.seconds
        assertEquals(20.seconds, counting(true, paused, resumedStart, resumedStart + 5.seconds))
    }

    @Test
    fun resetClearsTotalAndLaps() {
        assertEquals(Duration.ZERO, counting(false, Duration.ZERO, base, base + 50.seconds))
        assertEquals(emptyList(), splits(emptyList()))
    }

    @Test
    fun emptyLapsHaveNoSplits() {
        assertEquals(emptyList(), splits(emptyList()))
    }

    @Test
    fun lapsAreMonotonicNonDecreasing() {
        val laps = listOf(10.seconds, 10.seconds, 25.seconds, 60.seconds)
        for (index in 1 until laps.size) {
            assertTrue(laps[index] >= laps[index - 1], "lap $index went backwards")
        }
    }

    @Test
    fun lapSplitsSumToLastCumulativeTime() {
        val laps = listOf(10.seconds, 25.seconds, 45.seconds)
        val total = splits(laps).fold(Duration.ZERO) { acc, split -> acc + split }
        assertEquals(45.seconds, total)
    }

    @Test
    fun lapWhilePausedIsIgnored() {
        val laps = listOf(10.seconds)
        val kept = lapIfRunning(false, 10.seconds, base, base + 5.seconds, laps)
        assertEquals(laps, kept)
    }

    @Test
    fun lapWhileRunningAppendsCountingTime() {
        val laps = lapIfRunning(true, 10.seconds, base, base + 5.seconds, emptyList())
        assertEquals(listOf(15.seconds), laps)
    }

    @Test
    fun centisecondBoundaryAt99Vs100Millis() {
        assertEquals(99.milliseconds, counting(true, Duration.ZERO, base, base + 99.milliseconds))
        assertEquals(100.milliseconds, counting(true, Duration.ZERO, base, base + 100.milliseconds))
    }

    @Test
    fun zeroTotalAtEpochStart() {
        val epoch = Instant.fromEpochMilliseconds(0)
        assertEquals(7.seconds, counting(true, Duration.ZERO, epoch, epoch + 7.seconds))
    }

    @Test
    fun longRunOverTenHoursCountsCorrectly() {
        assertEquals(10.hours, counting(true, 9.hours, base, base + 1.hours))
        assertEquals(10.hours, counting(false, 10.hours, base, base + 5.hours))
    }

    @Test
    fun consecutiveLapsProduceCorrectSplits() {
        val laps = listOf(10.seconds, 30.seconds, 31.minutes)
        assertEquals(listOf(10.seconds, 20.seconds, 30.minutes + 30.seconds), splits(laps))
    }
}
