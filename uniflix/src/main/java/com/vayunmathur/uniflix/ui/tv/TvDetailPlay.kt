package com.vayunmathur.uniflix.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.uniflix.domain.MediaDetail
import com.vayunmathur.uniflix.domain.MediaType
import com.vayunmathur.uniflix.platform.DetailUiState
import com.vayunmathur.uniflix.platform.DetailViewModel

/**
 * The detail hero's primary action. Movies resolve S0/E0; shows start at the
 * first episode of the selected season (mirroring the phone detail). Play
 * routes to Watch, which surfaces the addon-manager link when nothing resolves.
 */
@Composable
internal fun TvDetailPlayButton(
    state: DetailUiState,
    detail: MediaDetail,
    type: MediaType,
    tmdbId: Int,
    mediaType: String,
    backStack: NavBackStack<TvRoute>,
    vm: DetailViewModel,
    playFocus: FocusRequester,
) {
    TvButton(
        text = stringResource(UiR.string.play),
        onClick = {
            if (detail.mediaType == MediaType.MOVIE) {
                vm.resolve(type, tmdbId)
                backStack.add(TvRoute.Watch(mediaType, tmdbId))
            } else {
                val first = state.episodes.firstOrNull()
                val season = first?.seasonNumber ?: state.selectedSeason
                val episode = first?.episodeNumber ?: 1
                vm.resolve(type, tmdbId, season, episode)
                backStack.add(TvRoute.Watch(mediaType, tmdbId, season, episode))
            }
        },
        modifier = Modifier.focusRequester(playFocus),
        icon = { IconPlay() },
        primary = true,
    )
}
