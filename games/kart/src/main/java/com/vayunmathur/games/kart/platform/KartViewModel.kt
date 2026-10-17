package com.vayunmathur.games.kart.platform

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.games.kart.data.GameMode
import com.vayunmathur.games.kart.data.KartDataStore
import com.vayunmathur.games.kart.data.KartProgress
import com.vayunmathur.games.kart.domain.GameInputLoop
import com.vayunmathur.games.kart.domain.KartRules
import com.vayunmathur.games.kart.domain.PlaceholderRace
import com.vayunmathur.games.kart.util.KartNative
import com.vayunmathur.library.util.AchievementsManager
import com.vayunmathur.library.util.DailyChallengeStore
import com.vayunmathur.library.util.DailyStreakReporter
import com.vayunmathur.library.work.DailyPuzzleReminder
import com.vayunmathur.library.work.DailyReminderSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the garage screen and the progress behind it.
 *
 * Casual mode is a single cup pointer: advancing writes `cup + 1` and leaves the lifetime
 * counters alone, so there is no cup list and no way back. Daily mode is one event per calendar
 * day, with its progress stamped with the day it belongs to.
 */
class KartViewModel(application: Application) : AndroidViewModel(application),
    KartGameActions, SettingsActions {

    val dataStore = KartDataStore(application)
    private val dailyStore = DailyChallengeStore(application, DAILY_KEY_PREFIX)
    private val reminderSettings = DailyReminderSettings(application, DAILY_KEY_PREFIX)

    private val _uiState = MutableStateFlow(KartUiState())
    val uiState: StateFlow<KartUiState> = _uiState.asStateFlow()

    val reminderEnabled: StateFlow<Boolean> = reminderSettings.enabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val reminderMinutesOfDay: StateFlow<Long> = reminderSettings.minutesOfDay
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            DailyPuzzleReminder.DEFAULT_MINUTES_OF_DAY,
        )

    val achievementsManager: AchievementsManager = run {
        val json = application.assets.open("achievements.json").bufferedReader().readText()
        KartAchievementsManager(application, json, dataStore, dailyStore)
    }

    /**
     * The TV lobby. No PQC identity ships in the scaffold: the bundle lambda returns null until
     * the TV service wires `:library:e2ee-p2p`, so network handshakes refuse cleanly while the
     * placeholder race runs on local seats.
     */
    private val kartHost = KartHost(
        context = application,
        publicBundle = { null },
        unsealSecret = { null },
    )

    private val _racePhase = MutableStateFlow<RacePhase>(RacePhase.Lobby(kartHost.lobby.value))
    /** Lobby → countdown → race → results. The garage reads this alongside [uiState]. */
    val racePhase: StateFlow<RacePhase> = _racePhase.asStateFlow()

    private var tickJob: Job? = null
    private var race: PlaceholderRace? = null

    /** This device's seat, so the HUD knows which kart is ours. Null until the race starts. */
    var localPlayerId: String? = null
        private set

    init {
        achievementsManager.checkExistingAchievements()

        viewModelScope.launch { kartHost.start() }

        viewModelScope.launch {
            kartHost.lobby.collectLatest { lobby ->
                if (_racePhase.value is RacePhase.Lobby) {
                    _racePhase.value = RacePhase.Lobby(lobby)
                }
            }
        }

        viewModelScope.launch {
            combine(
                dataStore.gameMode,
                dataStore.currentCup,
                dataStore.cupsCleared,
                dataStore.racesWon,
                dataStore.bestLapMs,
            ) { mode, cup, cleared, won, bestLap ->
                Progress(mode, cup, cleared, won, bestLap)
            }.combine(dailyStore.currentStreak) { progress, streak ->
                Session(progress.mode, progress.cup, progress.cleared, progress.won, progress.bestLap, streak)
            }.collectLatest { session -> load(session) }
        }
    }

    private data class Progress(
        val mode: GameMode,
        val cup: Int,
        val cleared: Int,
        val won: Int,
        val bestLap: Long,
    )

    /** The stored inputs that decide which cup is on screen. */
    private data class Session(
        val mode: GameMode,
        val cup: Int,
        val cleared: Int,
        val won: Int,
        val bestLap: Long,
        val streak: Long,
    )

    private suspend fun load(session: Session) {
        val daily = session.mode == GameMode.DAILY
        val day = dailyStore.todayEpochDay()
        if (daily) dataStore.ensureDailyDay(day)

        _uiState.value = KartUiState(
            cup = KartRules.cupAt(session.cup),
            progress = KartProgress(
                currentCup = session.cup,
                cupsCleared = session.cleared,
                racesWon = session.won,
                bestLapMs = session.bestLap,
            ),
            mode = session.mode,
            dailyStreak = session.streak,
            dailyDone = daily && dailyStore.lastCompletedDay() == day,
        )
    }

    override fun startRace() {
        // The garage button starts the lobby countdown; the tick loop banks the cup clear when
        // the race finishes. Daily mode keeps the old instant-clear until daily events race.
        val state = _uiState.value
        if (state.mode == GameMode.DAILY) {
            onDailyCleared()
            return
        }
        if (_racePhase.value !is RacePhase.Lobby) return
        // Solo seat for the scaffold: this device drives kart one until phones join.
        localPlayerId = localPlayerId ?: "local-${System.currentTimeMillis()}"
        val seat = requireNotNull(localPlayerId)
        kartHost.host.addLocalPlayer(seat, "You")
        viewModelScope.launch {
            for (count in COUNTDOWN_SECONDS downTo 1) {
                _racePhase.value = RacePhase.Countdown(count)
                delay(1_000)
            }
            beginRace(seat)
        }
    }

    /**
     * Feed one raw stick sample for the local seat. Called by the placeholder touch controls below;
     * gamepads and network phones arrive through the host instead.
     *
     * Builds the same [ControllerInput] shape every other source builds and routes it through the
     * same session, so the tick loop has exactly one input path per seat to read.
     */
    fun onLocalStick(playerId: String, x: Float, y: Float) {
        if (_racePhase.value !is RacePhase.Racing) return
        kartHost.host.onLocalInput(
            com.vayunmathur.sdk.controller.ControllerInput(
                playerId = playerId,
                seq = 0L,
                axes = listOf(x, y, 0f, 0f, 0f, 0f),
            ),
        )
    }

    /** A gamepad took or freed a seat. Hot-plug mid-race changes the next race, never this one. */
    fun onGamepadSeat(playerId: String, name: String, present: Boolean) {
        if (present) kartHost.host.addLocalPlayer(playerId, name.ifBlank { "Gamepad" })
        else kartHost.host.removeLocalPlayer(playerId)
    }

    /** One gamepad sample, already shaped as a [ControllerInput] by [GamepadDirect]. */
    fun onGamepadInput(input: com.vayunmathur.sdk.controller.ControllerInput) {
        if (_racePhase.value !is RacePhase.Racing) return
        kartHost.host.onLocalInput(input)
    }

    private fun beginRace(seat: String) {
        val playerIds = kartHost.lobby.value.slots.map { it.playerId }
        val seats = if (playerIds.isEmpty()) listOf(seat) else playerIds
        val newRace = PlaceholderRace(playerIds = seats)
        race = newRace
        kartHost.host.startRace()
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            var lastMs = android.os.SystemClock.uptimeMillis()
            while (isActive) {
                delay(TICK_MS)
                val nowMs = android.os.SystemClock.uptimeMillis()
                val dt = ((nowMs - lastMs).coerceAtMost(250L)) / 1_000f
                lastMs = nowMs
                val latest = kartHost.inputs.value
                for (id in seats) {
                    val sample = latest[id] ?: continue
                    val control = GameInputLoop.normalize(sample)
                    newRace.setControl(id, control)
                    KartNative.onDriveInput(
                        seats.indexOf(id),
                        control.throttle,
                        control.steering,
                        control.brake,
                    )
                    KartNative.setDrift(seats.indexOf(id), control.drift)
                    if (control.itemUse) {
                        newRace.useItem(id)
                        KartNative.useItem(seats.indexOf(id))
                    }
                }
                newRace.tick(dt)
                if (newRace.finished) {
                    finishRace(newRace)
                    return@launch
                }
                val order = newRace.leaderboard()
                _racePhase.value = RacePhase.Racing(
                    order = order,
                    lapProgress = order.associateWith { newRace.lapProgress(it) },
                    playerLap = newRace.lapOf(seat),
                    totalLaps = RACE_LAPS,
                )
            }
        }
    }

    private fun finishRace(finished: PlaceholderRace) {
        tickJob?.cancel()
        tickJob = null
        kartHost.host.endRace()
        val winner = finished.winner() ?: finished.leaderboard().first()
        val order = finished.leaderboard()
        _racePhase.value = RacePhase.Results(winner = winner, order = order)
        val state = _uiState.value
        viewModelScope.launch {
            val manager = achievementsManager
            manager.onAchievementUnlocked("first_race")
            if (winner == localPlayerId) {
                manager.onAchievementUnlocked("first_win")
            }
            val total = dataStore.recordCleared()
            manager.onProgressUpdated("cups_3", total)
            manager.onProgressUpdated("cups_10", total)
            KartRules.nextCup(state.progress.currentCup)?.let { dataStore.saveCup(it) }
        }
        race = null
    }

    /** Back to the lobby from results. Seats stay; the next Start Race counts down again. */
    override fun backToLobby() {
        _racePhase.value = RacePhase.Lobby(kartHost.lobby.value)
    }

    override fun onCleared() {
        tickJob?.cancel()
        kartHost.stop()
    }

    override fun nextCup() {
        val cup = _uiState.value.progress.currentCup
        viewModelScope.launch {
            KartRules.nextCup(cup)?.let { dataStore.saveCup(it) }
        }
    }

    override fun setGameMode(mode: GameMode) {
        viewModelScope.launch { dataStore.setGameMode(mode) }
    }

    override fun setReminderEnabled(enabled: Boolean) {
        viewModelScope.launch {
            reminderSettings.setEnabled(enabled)
            rescheduleReminder(enabled, reminderMinutesOfDay.value)
        }
    }

    override fun setReminderTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            val minutes = (hour * 60 + minute).toLong()
            reminderSettings.setMinutesOfDay(minutes)
            rescheduleReminder(reminderEnabled.value, minutes)
        }
    }

    private fun rescheduleReminder(enabled: Boolean, minutesOfDay: Long) {
        DailyPuzzleReminder.update(
            context = getApplication(),
            keyPrefix = DAILY_KEY_PREFIX,
            notificationId = REMINDER_NOTIFICATION_ID,
            enabled = enabled,
            hour = (minutesOfDay / 60).toInt(),
            minute = (minutesOfDay % 60).toInt(),
        )
    }

    fun dismissAchievementNotification() = achievementsManager.dismissNotification()

    private fun onDailyCleared() {
        viewModelScope.launch {
            val day = dailyStore.todayEpochDay()
            val manager = achievementsManager
            manager.onAchievementUnlocked("first_daily")
            val streak = dailyStore.recordDayCompleted(day)
            DailyStreakReporter.report(getApplication(), "kart", streak, day)
            manager.onProgressUpdated("daily_streak_7", streak.current.toInt())
            manager.onProgressUpdated("daily_streak_30", streak.current.toInt())
            load(uiState.value.toSession())
        }
    }

    private fun KartUiState.toSession() = Session(
        mode = mode,
        cup = progress.currentCup,
        cleared = progress.cupsCleared,
        won = progress.racesWon,
        bestLap = progress.bestLapMs,
        streak = dailyStreak,
    )

    companion object {
        /** Namespace shared by the daily-challenge store and its reminder. */
        const val DAILY_KEY_PREFIX = "kart_daily"

        private const val REMINDER_NOTIFICATION_ID = 5106

        /** Ticks per second for the placeholder race loop. */
        private const val TICK_MS = 50L

        /** Laps per race. Mirrors the placeholder's default. */
        private const val RACE_LAPS = 3

        /** Seconds of 3-2-1 before controls go live. */
        private const val COUNTDOWN_SECONDS = 3

        /**
         * Keeps date-derived daily seeds away from the cup indexes casual mode uses, and away
         * from the offsets the other games picked (500M pipes, 700M wordmaker, 900M unblockjam,
         * 1.1B nonogram, 1.3B arrows).
         */
        const val DAILY_SEED_OFFSET = 1_500_000_000L
    }
}
