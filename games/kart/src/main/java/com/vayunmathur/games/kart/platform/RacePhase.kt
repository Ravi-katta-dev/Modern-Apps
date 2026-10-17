package com.vayunmathur.games.kart.platform

import com.vayunmathur.games.kart.domain.KartControl
import com.vayunmathur.sdk.controller.LobbyState

/** Where a race stands. The garage is [Lobby]; [Results] banks progress on entry. */
sealed interface RacePhase {

    /** Seats filling up. [lobby] mirrors `ControllerHost.lobby`. */
    data class Lobby(val lobby: LobbyState) : RacePhase

    /** 3-2-1-GO, in seconds remaining. Controls are ignored. */
    data class Countdown(val secondsLeft: Int) : RacePhase

    /**
     * Live race. [order] is player ids (humans then AI) by progress descending; [lapProgress]
     * maps each id to 0..1 through its current lap; [playerLap] is this device's completed laps.
     */
    data class Racing(
        val order: List<String>,
        val lapProgress: Map<String, Float>,
        val playerLap: Int,
        val totalLaps: Int,
    ) : RacePhase

    /** Across the line. [winner] may be an AI id; [order] is the final standing. */
    data class Results(val winner: String, val order: List<String>) : RacePhase
}
