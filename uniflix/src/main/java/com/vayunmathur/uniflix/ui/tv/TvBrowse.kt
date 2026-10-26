package com.vayunmathur.uniflix.ui.tv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvGrid
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvLoading
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.tv.tvFullWidth
import com.vayunmathur.uniflix.R
import com.vayunmathur.uniflix.domain.MediaItem
import com.vayunmathur.uniflix.domain.MediaType
import com.vayunmathur.uniflix.platform.BrowseViewModel

/**
 * TV Movies/Shows hubs: the discover rows as a dense poster grid.
 * Same [BrowseViewModel] rows the phone grids show.
 */
@Composable
fun TvBrowseTab(mediaType: MediaType, onOpenDetail: (MediaItem) -> Unit) {
    val vm: BrowseViewModel = viewModel()
    val state = if (mediaType == MediaType.TV) vm.series else vm.movies
    LaunchedEffect(mediaType) {
        if (mediaType == MediaType.TV) vm.loadSeries() else vm.loadMovies()
    }
    val firstFocus = remember { FocusRequester() }
    TvInitialFocus(firstFocus, key = state.items.firstOrNull()?.let(::mediaKey))

    Column(Modifier.fillMaxSize()) {
        TvScreenHeader(
            title = stringResource(if (mediaType == MediaType.TV) R.string.shows else R.string.movies),
        )
        if (state.loading && state.items.isEmpty()) {
            TvLoading()
            return@Column
        }
        if (state.items.isEmpty()) {
            TvEmptyState(title = stringResource(R.string.no_results))
            return@Column
        }
        TvGrid(columns = 7) {
            items(state.items, key = ::mediaKey) { item ->
                MediaPosterCard(
                    item = item,
                    onOpen = { onOpenDetail(item) },
                    modifier = Modifier.focusIf(
                        mediaKey(item),
                        state.items.firstOrNull()?.let(::mediaKey),
                        firstFocus,
                    ),
                )
            }
            tvFullWidth(key = "end-anchor") {}
        }
    }
}

