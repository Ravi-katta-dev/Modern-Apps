package com.vayunmathur.uniflix.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvLoading
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.tv.tvContentPadding
import com.vayunmathur.uniflix.R
import com.vayunmathur.uniflix.domain.MediaItem
import com.vayunmathur.uniflix.domain.MediaType
import com.vayunmathur.uniflix.platform.BrowseViewModel

/**
 * TV Movies/Shows hubs: the discover rows as poster grids.
 *
 * Same [BrowseViewModel] rows the phone grids show — a grid, not shelves, because
 * this is the see-everything page.
 */
@Composable
fun TvBrowseTab(mediaType: MediaType, onOpenDetail: (MediaItem) -> Unit) {
    val vm: BrowseViewModel = viewModel()
    val state = if (mediaType == MediaType.TV) vm.series else vm.movies
    LaunchedEffect(mediaType) {
        if (mediaType == MediaType.TV) vm.loadSeries() else vm.loadMovies()
    }
    val gridFocus = remember { FocusRequester() }
    TvInitialFocus(gridFocus, key = state.items.firstOrNull()?.let(::mediaKey))

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
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            modifier = Modifier.fillMaxSize(),
            contentPadding = tvContentPadding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(state.items, key = ::mediaKey) { item ->
                MediaPosterCard(
                    item = item,
                    onOpen = { onOpenDetail(item) },
                    modifier = if (item == state.items.firstOrNull()) {
                        Modifier.focusRequester(gridFocus)
                    } else {
                        Modifier
                    },
                    width = 200.dp,
                )
            }
        }
    }
}
