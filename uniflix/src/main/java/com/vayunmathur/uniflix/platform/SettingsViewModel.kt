package com.vayunmathur.uniflix.platform

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.uniflix.data.TmdbKeyStore
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** Onboarding + settings state: the user-supplied TMDB API key. */
data class SettingsUiState(
    val apiKey: String = "",
    val draftKey: String = "",
    val saving: Boolean = false,
) {
    val onboarded: Boolean get() = apiKey.isNotBlank()
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val store = TmdbKeyStore(app)

    var state by mutableStateOf(SettingsUiState())
        private set

    init {
        store.apiKey
            .onEach { key ->
                state = state.copy(
                    apiKey = key,
                    draftKey = if (state.draftKey.isEmpty()) key else state.draftKey,
                )
            }
            .launchIn(viewModelScope)
    }

    fun onDraftChange(value: String) {
        state = state.copy(draftKey = value)
    }

    fun saveKey() {
        if (state.draftKey.isBlank()) return
        viewModelScope.launch {
            state = state.copy(saving = true)
            store.setApiKey(state.draftKey)
            state = state.copy(saving = false)
        }
    }
}
