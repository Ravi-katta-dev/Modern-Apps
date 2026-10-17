package com.vayunmathur.games.kart.platform

import com.vayunmathur.games.kart.data.GameMode
import com.vayunmathur.games.kart.data.KartCup
import com.vayunmathur.games.kart.data.KartProgress

/**
 * The UI contract between [KartViewModel] and the garage screen.
 *
 * The screen takes this interface rather than the ViewModel itself so it can be rendered by a
 * `@Preview` — which is what the store listing images are generated from. It lives in `platform`
 * rather than next to the screen so the dependency runs one way: `ui` depends on `platform`, and
 * the ViewModel implements this.
 *
 * Every member has a no-op default, so `Noop` is the whole implementation a preview needs.
 */
interface KartGameActions {
    /** Starts the lobby countdown; the tick loop banks the cup clear when the race finishes. */
    fun startRace() {}

    fun nextCup() {}
    fun setGameMode(mode: GameMode) {}

    /** Back to the lobby from the results screen. */
    fun backToLobby() {}

    companion object { val Noop: KartGameActions = object : KartGameActions {} }
}

/** What the garage screen needs to draw itself. */
data class KartUiState(
    val cup: KartCup? = null,
    val progress: KartProgress = KartProgress(),
    val mode: GameMode = GameMode.CASUAL,
    val dailyStreak: Long = 0,
    /** True once today's daily has been cleared, which swaps the race button for a note. */
    val dailyDone: Boolean = false,
)

interface SettingsActions {
    fun setReminderEnabled(enabled: Boolean) {}
    fun setReminderTime(hour: Int, minute: Int) {}

    companion object { val Noop: SettingsActions = object : SettingsActions {} }
}

data class SettingsUiState(
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 0,
)
