package com.vayunmathur.uniflix.ui.tv

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.tv.TvShelf
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.uniflix.R
import com.vayunmathur.uniflix.data.UniflixRepository
import com.vayunmathur.uniflix.data.watchKey
import com.vayunmathur.uniflix.domain.MediaType
import com.vayunmathur.uniflix.platform.DetailViewModel

/**
 * The episode shelf for a show: still cards with per-episode resume bars.
 * Progress reads the same continue-watching rows the phone detail overlays.
 */
@Composable
internal fun TvDetailEpisodeShelf(
    vm: DetailViewModel,
    type: MediaType,
    tmdbId: Int,
    mediaType: String,
    backStack: NavBackStack<TvRoute>,
) {
    val state = vm.state
    val context = LocalContext.current
    val repository = remember(context) { UniflixRepository.get(context) }
    val entries by repository.continueWatching.collectAsState(initial = emptyList())
    val progressByKey = remember(entries) {
        entries.associate { it.key to resumeProgress(it.positionMs, it.durationMs) }
    }
    TvShelf(title = stringResource(R.string.episodes)) {
        items(state.episodes, key = { "ep-${it.episodeNumber}" }) { episode ->
            EpisodeStillCard(
                episode = episode,
                progress = progressByKey[
                    watchKey(
                        tmdbId, type.name.lowercase(),
                        episode.seasonNumber, episode.episodeNumber,
                    ),
                ],
                onPlay = {
                    vm.resolve(type, tmdbId, episode.seasonNumber, episode.episodeNumber)
                    backStack.add(
                        TvRoute.Watch(
                            mediaType, tmdbId,
                            episode.seasonNumber, episode.episodeNumber,
                        ),
                    )
                },
            )
        }
    }
}
