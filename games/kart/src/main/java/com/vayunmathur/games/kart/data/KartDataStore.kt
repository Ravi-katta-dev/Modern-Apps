package com.vayunmathur.games.kart.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * Everything the game remembers.
 *
 * Casual progress is a single cup number plus lifetime counters; advancing clears nothing, it
 * just moves the pointer. Daily progress is stamped with the day it belongs to, so yesterday's
 * result can never bleed into today's.
 */
class KartDataStore(private val context: Context) {

    val currentCup: Flow<Int> = context.dataStore.data.map { it[CUP_KEY] ?: 0 }

    /** Lifetime count of cleared cups, which survives [saveCup] moving the pointer on. */
    val cupsCleared: Flow<Int> = context.dataStore.data.map { it[CLEARED_KEY] ?: 0 }

    /** Lifetime race wins, counted by the race flow once it exists. */
    val racesWon: Flow<Int> = context.dataStore.data.map { it[RACES_WON_KEY] ?: 0 }

    /** Best lap in milliseconds; zero means no lap has been timed yet. */
    val bestLapMs: Flow<Long> = context.dataStore.data.map { it[BEST_LAP_KEY] ?: 0L }

    val gameMode: Flow<GameMode> = context.dataStore.data.map { prefs ->
        prefs[GAME_MODE_KEY]?.let { runCatching { GameMode.valueOf(it) }.getOrNull() }
            ?: GameMode.CASUAL
    }

    // ---- Daily ----

    val dailyDay: Flow<Long> = context.dataStore.data.map { it[DAILY_DAY_KEY] ?: NO_DAY }

    suspend fun setGameMode(mode: GameMode) {
        context.dataStore.edit { it[GAME_MODE_KEY] = mode.name }
    }

    /** Moves to [cup] without touching the lifetime counters. */
    suspend fun saveCup(cup: Int) {
        context.dataStore.edit { prefs -> prefs[CUP_KEY] = cup }
    }

    /** Bumps and returns the lifetime cleared count. */
    suspend fun recordCleared(): Int {
        var total = 0
        context.dataStore.edit { prefs ->
            total = (prefs[CLEARED_KEY] ?: 0) + 1
            prefs[CLEARED_KEY] = total
        }
        return total
    }

    /**
     * Rolls the daily board over to [day] if it is not already there.
     *
     * The early return keeps re-entering daily mode from wiping work in progress.
     */
    suspend fun ensureDailyDay(day: Long) {
        context.dataStore.edit { prefs ->
            if (prefs[DAILY_DAY_KEY] == day) return@edit
            prefs[DAILY_DAY_KEY] = day
        }
    }

    private companion object {
        val CUP_KEY = intPreferencesKey("current_cup")
        val CLEARED_KEY = intPreferencesKey("cups_cleared")
        val RACES_WON_KEY = intPreferencesKey("races_won")
        val BEST_LAP_KEY = longPreferencesKey("best_lap_ms")
        val GAME_MODE_KEY = stringPreferencesKey("game_mode")

        val DAILY_DAY_KEY = longPreferencesKey("daily_day")

        /** Sentinel for "no daily played yet"; distinct from epoch day 0, which is a real date. */
        const val NO_DAY = Long.MIN_VALUE
    }
}
