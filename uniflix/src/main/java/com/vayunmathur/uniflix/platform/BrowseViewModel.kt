package com.vayunmathur.uniflix.platform

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.uniflix.data.TmdbApi
import com.vayunmathur.uniflix.data.TmdbKeyStore
import com.vayunmathur.uniflix.domain.MediaItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Discover grid state, shared shape for the Movies and Series hubs. */
data class BrowseUiState(
    val loading: Boolean = false,
    val items: List<MediaItem> = emptyList(),
)

class BrowseViewModel(app: Application) : AndroidViewModel(app) {
    private val api = TmdbApi()
    private val keys = TmdbKeyStore(app)

    var movies by mutableStateOf(BrowseUiState())
        private set
    var series by mutableStateOf(BrowseUiState())
        private set

    fun loadMovies() {
        viewModelScope.launch {
            val key = keys.apiKey.first()
            if (key.isBlank()) return@launch
            movies = movies.copy(loading = true)
            runCatching {
                movies = BrowseUiState(items = api.discoverMovies(key))
            }.onFailure {
                movies = movies.copy(loading = false)
                AppMessages.show("Movies failed to load: ${it.message}")
            }
        }
    }

    fun loadSeries() {
        viewModelScope.launch {
            val key = keys.apiKey.first()
            if (key.isBlank()) return@launch
            series = series.copy(loading = true)
            runCatching {
                series = BrowseUiState(items = api.discoverTv(key))
            }.onFailure {
                series = series.copy(loading = false)
                AppMessages.show("Series failed to load: ${it.message}")
            }
        }
    }
}
