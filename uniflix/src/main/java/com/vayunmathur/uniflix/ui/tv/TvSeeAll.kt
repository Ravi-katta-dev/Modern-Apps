package com.vayunmathur.uniflix.ui.tv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.uniflix.R
import com.vayunmathur.uniflix.data.ContinueWatchingEntry
import com.vayunmathur.uniflix.domain.MediaItem
import com.vayunmathur.uniflix.platform.HomeViewModel

/**
 * A full grid page behind a home "See all" action. Movies/Shows tabs cover
 * their own sections, so this serves Continue watching and the top-rated rows.
 */
@Composable
fun TvGridPage(
    backStack: NavBackStack<TvRoute>,
    kind: String,
) {
    val vm: HomeViewModel = viewModel()
    val state = vm.state
    val entries by vm.continueWatching.collectAsState(initial = emptyList())
    val openDetail: (MediaItem) -> Unit = {
        backStack.add(TvRoute.Detail(it.mediaType.name.lowercase(), it.tmdbId))
    }
    val onResume: (ContinueWatchingEntry) -> Unit = {
        backStack.add(TvRoute.Watch(it.mediaType, it.tmdbId, it.season, it.episode))
    }
    when (kind) {
        TV_GRID_CONTINUE -> ContinueGrid(
            title = stringResource(R.string.continue_watching),
            entries = entries,
            onResume = onResume,
        )
        TV_GRID_TOP_MOVIES -> PosterGrid(
            title = stringResource(R.string.top_rated_movies),
            loading = state.loading,
            items = state.topMovies,
            onOpenDetail = openDetail,
        )
        TV_GRID_TOP_TV -> PosterGrid(
            title = stringResource(R.string.top_rated_series),
            loading = state.loading,
            items = state.topTv,
            onOpenDetail = openDetail,
        )
        TV_GRID_TRENDING_MOVIES -> PosterGrid(
            title = stringResource(R.string.trending_movies),
            loading = state.loading,
            items = state.trendingMovies,
            onOpenDetail = openDetail,
        )
        else -> PosterGrid(
            title = stringResource(R.string.trending_series),
            loading = state.loading,
            items = state.trendingTv,
            onOpenDetail = openDetail,
        )
    }
}

/** Full continue-watching grid: landscape cards, two per row. */
@Composable
private fun ContinueGrid(
    title: String,
    entries: List<ContinueWatchingEntry>,
    onResume: (ContinueWatchingEntry) -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    val firstKey = entries.firstOrNull()?.let { "cw:${it.key}" }
    TvInitialFocus(firstFocus, key = firstKey)
    Column(Modifier.fillMaxSize()) {
        TvScreenHeader(title = title)
        if (entries.isEmpty()) {
            TvEmptyState(title = stringResource(R.string.nothing_in_progress))
            return@Column
        }
        TvGrid(columns = 6) {
            items(entries, key = { "cw:${it.key}" }, span = { GridItemSpan(2) }) { entry ->
                ContinueEntryCard(
                    entry = entry,
                    onResume = { onResume(entry) },
                    modifier = Modifier.focusIf("cw:${entry.key}", firstKey, firstFocus),
                )
            }
            tvFullWidth(key = "end-anchor") {}
        }
    }
}

/** Full poster grid for one top-rated/trending row. */
@Composable
private fun PosterGrid(
    title: String,
    loading: Boolean,
    items: List<MediaItem>,
    onOpenDetail: (MediaItem) -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    val firstKey = items.firstOrNull()?.let(::mediaKey)
    TvInitialFocus(firstFocus, key = firstKey)
    Column(Modifier.fillMaxSize()) {
        TvScreenHeader(title = title)
        if (loading && items.isEmpty()) {
            TvLoading()
            return@Column
        }
        if (items.isEmpty()) {
            TvEmptyState(title = stringResource(R.string.no_results))
            return@Column
        }
        TvGrid(columns = 7) {
            items(items, key = ::mediaKey) { item ->
                MediaPosterCard(
                    item = item,
                    onOpen = { onOpenDetail(item) },
                    modifier = Modifier.focusIf(mediaKey(item), firstKey, firstFocus),
                )
            }
            tvFullWidth(key = "end-anchor") {}
        }
    }
}

