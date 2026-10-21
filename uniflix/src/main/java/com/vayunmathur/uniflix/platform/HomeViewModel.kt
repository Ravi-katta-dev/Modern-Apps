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
import com.vayunmathur.uniflix.data.UniflixRepository
import com.vayunmathur.uniflix.domain.MediaItem
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** Home rows: trending + top-rated, gated on a configured TMDB key. */
data class HomeUiState(
    val hasKey: Boolean = false,
    val loading: Boolean = false,
    val trendingMovies: List<MediaItem> = emptyList(),
    val trendingTv: List<MediaItem> = emptyList(),
    val topMovies: List<MediaItem> = emptyList(),
    val topTv: List<MediaItem> = emptyList(),
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val api = TmdbApi()
    private val keys = TmdbKeyStore(app)
    val repository = UniflixRepository.get(app)

    var state by mutableStateOf(HomeUiState())
        private set

    init {
        keys.apiKey
            .onEach { key ->
                state = state.copy(hasKey = key.isNotBlank())
                if (key.isNotBlank()) refresh(key)
            }
            .launchIn(viewModelScope)
    }

    fun refresh(apiKey: String = "") {
        viewModelScope.launch {
            val key = apiKey.ifEmpty { keys.apiKey.first() }
            if (key.isBlank()) {
                state = state.copy(hasKey = false)
                return@launch
            }
            state = state.copy(loading = true, hasKey = true)
            runCatching {
                val movies = api.trendingMovies(key)
                val tv = api.trendingTv(key)
                val topM = api.topRatedMovies(key)
                val topT = api.topRatedTv(key)
                state = state.copy(
                    loading = false,
                    trendingMovies = movies,
                    trendingTv = tv,
                    topMovies = topM,
                    topTv = topT,
                )
            }.onFailure {
                state = state.copy(loading = false)
                AppMessages.show("Catalog failed to load: ${it.message}")
            }
        }
    }

    /** Continue-watching row source, newest first. */
    val continueWatching get() = repository.continueWatching

    /** Watchlist row source, newest first. */
    val watchlist get() = repository.watchlist

    fun watchlistSnapshot() = combine(repository.watchlist, repository.continueWatching) { w, _ -> w }
}
