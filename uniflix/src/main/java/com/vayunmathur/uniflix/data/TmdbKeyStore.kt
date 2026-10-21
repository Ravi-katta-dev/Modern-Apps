package com.vayunmathur.uniflix.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.uniflixDataStore by preferencesDataStore(name = "uniflix_config")

/** TMDB API key storage. No default — the user supplies their own key at onboarding. */
class TmdbKeyStore(private val context: Context) {
    val apiKey: Flow<String> = context.uniflixDataStore.data.map { it[KEY_TMDB] ?: "" }

    suspend fun setApiKey(key: String) {
        context.uniflixDataStore.edit { it[KEY_TMDB] = key.trim() }
    }

    companion object {
        private val KEY_TMDB = stringPreferencesKey("tmdb_api_key")
    }
}
