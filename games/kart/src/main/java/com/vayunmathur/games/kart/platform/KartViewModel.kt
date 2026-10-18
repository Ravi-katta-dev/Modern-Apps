package com.vayunmathur.games.kart.platform

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.games.kart.data.GameMode
import com.vayunmathur.games.kart.data.KartDataStore
import com.vayunmathur.games.kart.data.KartProgress
import com.vayunmathur.games.kart.domain.GameInputLoop
import com.vayunmathur.games.kart.domain.KartRules
import com.vayunmathur.games.kart.domain.LapTracker
import com.vayunmathur.games.kart.domain.PlaceholderRace
import com.vayunmathur.games.kart.domain.RumbleDecider
import com.vayunmathur.games.kart.util.KartNative
import com.vayunmathur.library.util.AchievementsManager
import com.vayunmathur.library.util.DailyChallengeStore
import com.vayunmathur.library.util.DailyStreakReporter
import com.vayunmathur.library.work.DailyPuzzleReminder
import com.vayunmathur.library.work.DailyReminderSettings
import com.vayunmathur.sdk.controller.RumbleFeedback
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
     * The TV lobby, backed by a real post-quantum identity.
     *
     * The identity loads off the main thread (keygen on first run touches the
     * native PQC library): until it lands, [KartHost] refuses handshakes
     * cleanly — the same posture the `{ null }` scaffold had, but only for
     * the cold-start window rather than forever. The device-key persistence
     * lives in [KartPairingStore] alongside the identity, outside the backup
     * set, so a remembered phone re-pairs silently across restarts.
     */
    private val pairingStore = KartPairingStore(application)

    @Volatile
    private var pqcIdentity: com.vayunmathur.e2ee.PqcIdentity? = null

    private val kartHost = KartHost(
        context = application,
        publicBundle = { pqcIdentity?.publicBundle },
        unsealSecret = { sealed ->
            val identity = pqcIdentity ?: return@KartHost null
            runCatching { identity.decrypt(sealed) }
                .getOrNull()
                ?.takeIf { it.size == com.vayunmathur.sdk.controller.ControllerKeys.SECRET_BYTES }
        },
        pairingStore = pairingStore,
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

        // Load (or mint) the PQC identity off the main thread, then open the
        // lobby. First run pays keygen; every later start reads persisted keys.
        viewModelScope.launch {
            pqcIdentity = runCatching {
                com.vayunmathur.e2ee.PqcIdentity.loadOrCreate(pairingStore, prefix = "kartTv")
            }.getOrNull()
            kartHost.start()
        }

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
        kartHost.host?.addLocalPlayer(seat, "You")
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
        kartHost.host?.onLocalInput(
            com.vayunmathur.sdk.controller.ControllerInput(
                playerId = playerId,
                seq = 0L,
                axes = listOf(x, y, 0f, 0f, 0f, 0f),
            ),
        )
    }

    /** A gamepad took or freed a seat. Hot-plug mid-race changes the next race, never this one. */
    fun onGamepadSeat(playerId: String, name: String, present: Boolean) {
        if (present) kartHost.host?.addLocalPlayer(playerId, name.ifBlank { "Gamepad" })
        else kartHost.host?.removeLocalPlayer(playerId)
    }

    /** One gamepad sample, already shaped as a [ControllerInput] by [GamepadDirect]. */
    fun onGamepadInput(input: com.vayunmathur.sdk.controller.ControllerInput) {
        if (_racePhase.value !is RacePhase.Racing) return
        kartHost.host?.onLocalInput(input)
    }

    private fun beginRace(seat: String) {
        val playerIds = kartHost.lobby.value.slots.map { it.playerId }
        val seats = if (playerIds.isEmpty()) listOf(seat) else playerIds
        if (KartNative.isAvailable) {
            beginNativeRace(seat, seats)
        } else {
            beginPlaceholderRace(seat, seats)
        }
    }

    /**
     * The native race: the Rust engine owns the sim (seats, AI, items, laps) and ticks itself on
     * its own render thread; this loop only feeds inputs and polls standings for the HUD.
     */
    private fun beginNativeRace(seat: String, seats: List<String>) {
        KartNative.startRace(seats)
        kartHost.host?.startRace()
        val itemLatch = mutableMapOf<String, Boolean>()
        val localSeats = localPlayerIds()
        var prevOrder: List<String> = emptyList()
        val lastBuzzMs = mutableMapOf<String, Long>()
        var prevLap = 0
        var lapState = LapTracker.State(lastIncrementMs = android.os.SystemClock.uptimeMillis())
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                val latest = kartHost.inputs.value
                for (id in seats) {
                    val sample = latest[id] ?: continue
                    val control = GameInputLoop.normalize(sample)
                    val index = seats.indexOf(id)
                    KartNative.onDriveInput(index, control.throttle, control.steering, control.brake)
                    KartNative.setDrift(index, control.drift)
                    // Rising edge per seat: holding item fires once (the engine
                    // consumes the edge on its next step).
                    val fire = control.itemUse && itemLatch[id] != true
                    itemLatch[id] = control.itemUse
                    if (fire) KartNative.useItem(index)
                }
                val standings = parseStandings(KartNative.getStandingsJson())
                if (standings.isEmpty()) continue
                if (standings.any { it.lap >= RACE_LAPS }) {
                    val ordered = standings.sortedBy { it.place }
                    finishRace(
                        winner = ordered.first().player,
                        order = ordered.map { it.player },
                    )
                    return@launch
                }
                val ordered = standings.sortedBy { it.place }
                val order = ordered.map { it.player }
                buzzPlaceLosers(prevOrder, order, localSeats, lastBuzzMs)
                prevOrder = order
                val nowMs = android.os.SystemClock.uptimeMillis()
                val lap = ordered.firstOrNull { it.player == seat }?.lap ?: 0
                lapState = LapTracker.onLapTick(prevLap, lap, nowMs, lapState)
                prevLap = lap
                _racePhase.value = RacePhase.Racing(
                    order = order,
                    lapProgress = ordered.associate { it.player to it.progress },
                    playerLap = lap,
                    totalLaps = RACE_LAPS,
                    lastLapMs = lapState.times.lastLapMs,
                    bestLapMs = lapState.times.bestLapMs,
                )
            }
        }
    }

    private fun beginPlaceholderRace(seat: String, seats: List<String>) {
        val newRace = PlaceholderRace(playerIds = seats)
        race = newRace
        kartHost.host?.startRace()
        val localSeats = localPlayerIds()
        var prevOrder: List<String> = emptyList()
        val lastBuzzMs = mutableMapOf<String, Long>()
        var prevLap = 0
        var lapState = LapTracker.State(lastIncrementMs = android.os.SystemClock.uptimeMillis())
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
                    val order = newRace.leaderboard()
                    finishRace(
                        winner = newRace.winner() ?: order.first(),
                        order = order,
                    )
                    return@launch
                }
                val order = newRace.leaderboard()
                buzzPlaceLosers(prevOrder, order, localSeats, lastBuzzMs)
                prevOrder = order
                val lap = newRace.lapOf(seat)
                lapState = LapTracker.onLapTick(prevLap, lap, nowMs, lapState)
                prevLap = lap
                _racePhase.value = RacePhase.Racing(
                    order = order,
                    lapProgress = order.associateWith { newRace.lapProgress(it) },
                    playerLap = lap,
                    totalLaps = RACE_LAPS,
                    lastLapMs = lapState.times.lastLapMs,
                    bestLapMs = lapState.times.bestLapMs,
                )
            }
        }
    }
    private fun finishRace(winner: String, order: List<String>) {
        tickJob?.cancel()
        tickJob = null
        // Celebration buzz for every remote phone still on the grid.
        celebrate(order)
        kartHost.host?.endRace()
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

    /**
     * Buzz the remote phones whose place worsened since the last tick. Local seats (this device
     * plus gamepads) and AI karts are skipped before the host — its send no-ops for non-remote
     * slots anyway, but there is no reason to ask. Callers thread [prevOrder] through ticks and
     * let this update [lastBuzzMs] with who buzzed when.
     */
    private fun buzzPlaceLosers(
        prevOrder: List<String>,
        order: List<String>,
        localSeats: Set<String>,
        lastBuzzMs: MutableMap<String, Long>,
    ) {
        val nowMs = android.os.SystemClock.uptimeMillis()
        val buzz = RumbleDecider.decide(
            prevOrder = prevOrder,
            newOrder = order,
            localPlayerIds = localSeats,
            lastBuzzMs = lastBuzzMs,
            nowMs = nowMs,
        )
        for (id in buzz) {
            kartHost.host?.rumble(id, RumbleFeedback(durationMs = RUMBLE_HIT_MS, highFreq = 1f))
            lastBuzzMs[id] = nowMs
        }
    }

    /** One short buzz to every remote phone on the grid, so the finish lands on all of them. */
    private fun celebrate(order: List<String>) {
        val locals = localPlayerIds()
        for (id in order) {
            if (id in locals || id.startsWith("ai-")) continue
            kartHost.host?.rumble(id, RumbleFeedback(durationMs = RUMBLE_FINISH_MS, highFreq = 1f))
        }
    }

    /** Every seat driven from this device: the touch seat plus any plugged-in gamepads. */
    private fun localPlayerIds(): Set<String> =
        kartHost.lobby.value.slots.filter { !it.remote }.map { it.playerId }.toSet()

    /**
     * One native standings row. Parsed by hand (not kotlinx.serialization): the engine emits a
     * fixed tiny shape and the game must never crash the tick on a malformed frame.
     */
    private data class NativeStanding(
        val player: String,
        val lap: Int,
        val progress: Float,
        val place: Int,
    )

    private fun parseStandings(json: String): List<NativeStanding> {
        val text = json.trim()
        if (!text.startsWith("[") || !text.endsWith("]")) return emptyList()
        val rows = text.removePrefix("[").removeSuffix("]").split("},")
        if (rows.size == 1 && rows[0].isBlank()) return emptyList()
        return rows.mapNotNull { row ->
            val obj = row.trim().removeSuffix("}").removePrefix("{")
            fun field(name: String): String? {
                val key = "\"$name\":"
                val at = obj.indexOf(key)
                if (at < 0) return null
                val rest = obj.substring(at + key.length).trim()
                return if (rest.startsWith("\"")) {
                    rest.removePrefix("\"").substringBefore("\"")
                } else {
                    rest.substringBefore(",").trim()
                }
            }
            val player = field("player") ?: return@mapNotNull null
            val lap = field("lap")?.toIntOrNull() ?: return@mapNotNull null
            val progress = field("progress")?.toFloatOrNull() ?: return@mapNotNull null
            val place = field("place")?.toIntOrNull() ?: return@mapNotNull null
            NativeStanding(player, lap, progress, place)
        }
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

        /** Buzz length for a place-loss hit. */
        private const val RUMBLE_HIT_MS = 180

        /** Buzz length for the finish-line celebration. */
        private const val RUMBLE_FINISH_MS = 250

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
