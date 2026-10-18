package com.vayunmathur.games.kart.domain

import com.vayunmathur.sdk.controller.ControllerButton
import com.vayunmathur.sdk.controller.ControllerInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KartRaceTest {

    private fun input(
        seq: Long = 1,
        buttons: Long = 0L,
        axes: List<Float> = listOf(0f, -1f, 0f, 0f, 0f, 0f),
    ) = ControllerInput(playerId = "p1", seq = seq, buttons = buttons, axes = axes)

    @Test
    fun fullThrottleAdvances() {
        val race = PlaceholderRace(playerIds = listOf("p1"), aiCount = 0)
        race.setControl("p1", GameInputLoop.normalize(input()))
        race.tick(1f)
        assertTrue(race.lapProgress("p1") > 0f)
    }

    @Test
    fun neutralStaysPut() {
        val race = PlaceholderRace(playerIds = listOf("p1"), aiCount = 0)
        race.setControl("p1", KartControl.NEUTRAL)
        race.tick(5f)
        assertEquals(0f, race.lapProgress("p1"))
    }

    @Test
    fun steeringScrubsSpeed() {
        val straight = PlaceholderRace(playerIds = listOf("p1"), aiCount = 0)
        straight.setControl("p1", KartControl(throttle = 1f, steering = 0f))
        straight.tick(1f)
        val turning = PlaceholderRace(playerIds = listOf("p1"), aiCount = 0)
        turning.setControl("p1", KartControl(throttle = 1f, steering = 1f))
        turning.tick(1f)
        assertTrue(turning.lapProgress("p1") < straight.lapProgress("p1"))
    }

    @Test
    fun aiFinishesAlone() {
        // No human input at all: the AI still completes laps, so a solo race always ends.
        val race = PlaceholderRace(playerIds = listOf("p1"), aiCount = 1, laps = 1)
        repeat(120) { race.tick(1f) }
        assertTrue(race.finished)
    }

    @Test
    fun winnerIsFirstAcross() {
        val race = PlaceholderRace(playerIds = listOf("p1", "p2"), aiCount = 0, laps = 1)
        race.setControl("p1", KartControl(throttle = 1f))
        race.setControl("p2", KartControl(throttle = 0.5f))
        repeat(120) { race.tick(1f) }
        assertTrue(race.finished)
        assertEquals("p1", race.winner())
        assertEquals(listOf("p1", "p2"), race.leaderboard())
    }

    @Test
    fun deadzoneHoldsCenter() {
        assertEquals(0f, GameInputLoop.curve(0.1f))
        assertEquals(0f, GameInputLoop.curve(-0.14f))
    }

    @Test
    fun curveReachesFullThrow() {
        assertEquals(1f, GameInputLoop.curve(1f))
        assertEquals(-1f, GameInputLoop.curve(-1f))
    }

    @Test
    fun stickUpIsThrottle() {
        // Left stick pushed fully up: stickY = -1.
        val control = GameInputLoop.normalize(input(axes = listOf(0f, -1f, 0f, 0f, 0f, 0f)))
        assertEquals(1f, control.throttle)
        assertEquals(0f, control.brake)
    }

    @Test
    fun stickDownIsBrake() {
        val control = GameInputLoop.normalize(input(axes = listOf(0f, 1f, 0f, 0f, 0f, 0f)))
        assertEquals(0f, control.throttle)
        assertTrue(control.brake > 0f)
    }

    @Test
    fun shoulderIsDriftAndAIsItem() {
        val control = GameInputLoop.normalize(
            input(buttons = ControllerButton.RIGHT_SHOULDER.bit or ControllerButton.A.bit),
        )
        assertTrue(control.drift)
        assertTrue(control.itemUse)
    }

    @Test
    fun dpadSteersAtRest() {
        val control = GameInputLoop.normalize(
            input(buttons = ControllerButton.DPAD_LEFT.bit, axes = List(6) { 0f }),
        )
        assertTrue(control.steering < 0f)
    }

    @Test
    fun itemFiresOncePerPress() {
        // The loop reports the button state every tick; the race latches the rising edge, so a
        // held button fires once no matter how many ticks it spans.
        val race = PlaceholderRace(playerIds = listOf("p1"), aiCount = 0)
        race.setControl("p1", KartControl(throttle = 1f, itemUse = true))
        assertTrue(race.useItem("p1"))
        assertEquals(false, race.useItem("p1"))
        assertEquals(false, race.useItem("p1"))
        // Release and press again: a fresh edge, a fresh fire.
        race.setControl("p1", KartControl(throttle = 1f, itemUse = false))
        assertEquals(false, race.useItem("p1"))
        race.setControl("p1", KartControl(throttle = 1f, itemUse = true))
        assertTrue(race.useItem("p1"))
    }

    @Test
    fun driftKeepsMoreSpeedThanGrip() {
        // Full lock while drifting must beat full lock on grip, or drift is decoration.
        val drift = PlaceholderRace(playerIds = listOf("p1"), aiCount = 0)
        drift.setControl("p1", KartControl(throttle = 1f, steering = 1f, drift = true))
        drift.tick(1f)
        val grip = PlaceholderRace(playerIds = listOf("p1"), aiCount = 0)
        grip.setControl("p1", KartControl(throttle = 1f, steering = 1f, drift = false))
        grip.tick(1f)
        assertTrue(drift.lapProgress("p1") > grip.lapProgress("p1"))
    }

    @Test
    fun driftStillScrubs() {
        // …but not for free: drifting through full lock is slower than driving straight.
        val drift = PlaceholderRace(playerIds = listOf("p1"), aiCount = 0)
        drift.setControl("p1", KartControl(throttle = 1f, steering = 1f, drift = true))
        drift.tick(1f)
        val straight = PlaceholderRace(playerIds = listOf("p1"), aiCount = 0)
        straight.setControl("p1", KartControl(throttle = 1f, steering = 0f))
        straight.tick(1f)
        assertTrue(drift.lapProgress("p1") < straight.lapProgress("p1"))
    }

    @Test
    fun placeLossTriggersRumble() {
        // remote-1 drops from 2nd to 3rd (got passed); remote-2 climbs past them.
        val buzzed = RumbleDecider.decide(
            prevOrder = listOf("you", "remote-1", "remote-2"),
            newOrder = listOf("you", "remote-2", "remote-1"),
            localPlayerIds = setOf("you"),
            lastBuzzMs = emptyMap(),
            nowMs = 10_000L,
        )
        assertEquals(listOf("remote-1"), buzzed)
    }

    @Test
    fun noRumbleOnImprovementOrUnchanged() {
        // remote-2 climbs past the local seat; nobody remote worsens.
        assertEquals(
            emptyList(),
            RumbleDecider.decide(
                prevOrder = listOf("remote-1", "you", "remote-2"),
                newOrder = listOf("remote-1", "remote-2", "you"),
                localPlayerIds = setOf("you"),
                lastBuzzMs = emptyMap(),
                nowMs = 10_000L,
            ),
        )
        assertEquals(
            emptyList(),
            RumbleDecider.decide(
                prevOrder = listOf("you", "remote-1"),
                newOrder = listOf("you", "remote-1"),
                localPlayerIds = setOf("you"),
                lastBuzzMs = emptyMap(),
                nowMs = 10_000L,
            ),
        )
    }

    @Test
    fun cooldownSuppressesRepeatBuzz() {
        val prev = listOf("remote-1", "you")
        val new = listOf("you", "remote-1")
        val locals = setOf("you")
        val first = RumbleDecider.decide(prev, new, locals, emptyMap(), nowMs = 10_000L)
        assertEquals(listOf("remote-1"), first)
        assertEquals(
            emptyList(),
            RumbleDecider.decide(prev, new, locals, mapOf("remote-1" to 10_000L), nowMs = 10_500L),
        )
        assertEquals(
            listOf("remote-1"),
            RumbleDecider.decide(prev, new, locals, mapOf("remote-1" to 10_000L), nowMs = 11_500L),
        )
    }

    @Test
    fun localSeatsAndAiNeverBuzz() {
        // The touch seat, a plugged-in gamepad, and AI rivals all worsen — none buzz; only the
        // remote phone that dropped from 1st to 4th does.
        val buzzed = RumbleDecider.decide(
            prevOrder = listOf("remote-1", "you", "pad-1", "ai-0"),
            newOrder = listOf("you", "pad-1", "ai-0", "remote-1"),
            localPlayerIds = setOf("you", "pad-1"),
            lastBuzzMs = emptyMap(),
            nowMs = 10_000L,
        )
        assertEquals(listOf("remote-1"), buzzed)
    }

    @Test
    fun firstTickAndNewcomersStaySilent() {
        assertEquals(
            emptyList(),
            RumbleDecider.decide(
                prevOrder = emptyList(),
                newOrder = listOf("you", "remote-1"),
                localPlayerIds = setOf("you"),
                lastBuzzMs = emptyMap(),
                nowMs = 10_000L,
            ),
        )
        assertEquals(
            emptyList(),
            RumbleDecider.decide(
                prevOrder = listOf("you"),
                newOrder = listOf("you", "remote-1"),
                localPlayerIds = setOf("you"),
                lastBuzzMs = emptyMap(),
                nowMs = 10_000L,
            ),
        )
    }

    @Test
    fun lapIncrementRecordsDurationAndBest() {
        var state = LapTracker.State(lastIncrementMs = 1_000L)
        state = LapTracker.onLapTick(prevLap = 0, lap = 1, nowMs = 26_000L, state = state)
        assertEquals(25_000L, state.times.lastLapMs)
        assertEquals(25_000L, state.times.bestLapMs)
        state = LapTracker.onLapTick(prevLap = 1, lap = 2, nowMs = 49_000L, state = state)
        assertEquals(23_000L, state.times.lastLapMs)
        assertEquals(23_000L, state.times.bestLapMs)
    }

    @Test
    fun sameLapTickKeepsState() {
        val state = LapTracker.State(lastIncrementMs = 1_000L)
        assertEquals(state, LapTracker.onLapTick(prevLap = 1, lap = 1, nowMs = 26_000L, state = state))
    }

    @Test
    fun backmarkerPaceBoostsLeaderStaysBase() {
        val wobble = 0.95f
        val base = PlaceholderRace.AI_PACE * wobble
        assertEquals(base, PlaceholderRace.effectiveAiPace(wobble, 0f))
        assertTrue(PlaceholderRace.effectiveAiPace(wobble, 0.10f) > base)
    }

    @Test
    fun rubberBoostStepsAndShrinks() {
        assertEquals(
            PlaceholderRace.RUBBER_GAIN,
            PlaceholderRace.rubberBoost(PlaceholderRace.RUBBER_STEP),
        )
        assertEquals(0f, PlaceholderRace.rubberBoost(PlaceholderRace.RUBBER_STEP * 0.5f))
        assertTrue(PlaceholderRace.rubberBoost(0.20f) > PlaceholderRace.rubberBoost(0.05f))
        assertEquals(0f, PlaceholderRace.rubberBoost(-0.5f))
    }

    @Test
    fun aiPaceCapHolds() {
        assertTrue(PlaceholderRace.effectiveAiPace(1f, 10f) <= PlaceholderRace.AI_PACE_MAX)
        assertTrue(PlaceholderRace.effectiveAiPace(0.90f, 10f) <= PlaceholderRace.AI_PACE_MAX)
    }
}
