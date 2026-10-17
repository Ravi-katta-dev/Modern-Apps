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
}
