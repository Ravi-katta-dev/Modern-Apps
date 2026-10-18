package com.vayunmathur.games.kart.domain

/**
 * Decides which remote karts buzz when the standings shuffle.
 *
 * Pure function of the previous tick's order, the new order, and per-player cooldowns, so the
 * tick loops stay thin and the decision is a JVM test. A player buzzes only when their place
 * worsens tick-over-tick (they got passed or hit); improving, holding still, joining mid-race,
 * and the very first tick never buzz. Local seats (this device plus plugged-in gamepads) and AI
 * karts never buzz: the host only holds sockets for remote phones (its rumble send no-ops for
 * every other slot), but the loop skips them before it even gets there. A per-player cooldown
 * (~1.5 s) stops a multi-pass pile-up from becoming a buzz storm.
 */
object RumbleDecider {

    /** Minimum milliseconds between buzzes for the same player. */
    const val COOLDOWN_MS = 1_500L

    /**
     * Player ids to buzz: remote humans whose place worsened from [prevOrder] to [newOrder] and
     * whose last buzz in [lastBuzzMs] is at least [cooldownMs] old.
     *
     * Orders are player ids, leaders first. Ids in [localPlayerIds] are this device's seats and
     * never buzz; ids starting with `ai-` are engine rivals with no phone to buzz. Ids with no
     * previous place (joined mid-race) have no baseline and stay silent.
     */
    fun decide(
        prevOrder: List<String>,
        newOrder: List<String>,
        localPlayerIds: Set<String>,
        lastBuzzMs: Map<String, Long>,
        nowMs: Long,
        cooldownMs: Long = COOLDOWN_MS,
    ): List<String> {
        if (prevOrder.isEmpty()) return emptyList()
        val prevPlace = prevOrder.withIndex().associate { (place, id) -> id to place }
        return newOrder.withIndex().mapNotNull { (place, id) ->
            if (id in localPlayerIds || id.startsWith("ai-")) return@mapNotNull null
            val was = prevPlace[id] ?: return@mapNotNull null
            if (place <= was) return@mapNotNull null
            val last = lastBuzzMs[id]
            if (last != null && nowMs - last < cooldownMs) return@mapNotNull null
            id
        }
    }
}
