package com.vayunmathur.games.kart.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * Everything the game remembers.
 *
 * A single cup pointer plus lifetime counters; advancing moves the pointer, it clears nothing.
 */
class KartDataStore(private val context: Context) {

    val currentCup: Flow<Int> = context.dataStore.data.map { it[CUP_KEY] ?: 0 }

    /** Lifetime race wins, counted by the race flow once it exists. */
    val racesWon: Flow<Int> = context.dataStore.data.map { it[RACES_WON_KEY] ?: 0 }

    /** Best lap in milliseconds; zero means no lap has been timed yet. */
    val bestLapMs: Flow<Long> = context.dataStore.data.map { it[BEST_LAP_KEY] ?: 0L }

    /** Moves to [cup] without touching the lifetime counters. */
    suspend fun saveCup(cup: Int) {
        context.dataStore.edit { prefs -> prefs[CUP_KEY] = cup }
    }

    private companion object {
        val CUP_KEY = intPreferencesKey("current_cup")
        val RACES_WON_KEY = intPreferencesKey("races_won")
        val BEST_LAP_KEY = longPreferencesKey("best_lap_ms")
    }
}
