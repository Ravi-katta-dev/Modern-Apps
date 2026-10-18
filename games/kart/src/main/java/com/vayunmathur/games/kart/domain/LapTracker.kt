package com.vayunmathur.games.kart.domain

/**
 * Lap times for the HUD: the last completed local lap and the best one this race.
 *
 * Milliseconds on the same clock the race loop reads; null until the first lap completes.
 */
data class LapTimes(
    val lastLapMs: Long? = null,
    val bestLapMs: Long? = null,
)

/**
 * Pure lap-increment bookkeeping for the race loops.
 *
 * The ViewModel keeps the [State] and feeds each tick's lap count; when the local lap count
 * rises, the time since the previous increment becomes the last lap (and the best, if it beats
 * it). Seed [State.lastIncrementMs] with the race-start timestamp so the opening lap times
 * itself from green. Pure so the increment detection is a JVM test; the loops stay thin.
 */
object LapTracker {

    /** Carried across ticks: when the last lap completed, plus the times so far. */
    data class State(
        val lastIncrementMs: Long? = null,
        val times: LapTimes = LapTimes(),
    )

    /**
     * Fold one tick's lap count into [state]. Returns [state] unchanged unless [lap] rose above
     * [prevLap]. The increment timestamp always advances to [nowMs], so the next lap times
     * itself from this tick.
     */
    fun onLapTick(prevLap: Int, lap: Int, nowMs: Long, state: State): State {
        if (lap <= prevLap) return state
        val duration = state.lastIncrementMs?.let { (nowMs - it).coerceAtLeast(0L) }
        val times = if (duration == null) {
            state.times
        } else {
            LapTimes(
                lastLapMs = duration,
                bestLapMs = state.times.bestLapMs?.coerceAtMost(duration) ?: duration,
            )
        }
        return State(lastIncrementMs = nowMs, times = times)
    }
}
