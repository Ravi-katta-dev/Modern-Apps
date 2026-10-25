package com.vayunmathur.uniflix.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.tv.TvBackdrop
import com.vayunmathur.library.tv.TvDimens
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvHeroText
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvLoading
import com.vayunmathur.library.tv.TvShelf
import com.vayunmathur.library.tv.tvContentStart
import com.vayunmathur.uniflix.R
import com.vayunmathur.uniflix.data.ContinueWatchingEntry
import com.vayunmathur.uniflix.domain.MediaItem
import com.vayunmathur.uniflix.platform.HomeViewModel

/**
 * TV home: an immersive hero following the focused title, with content shelves below.
 *
 * The backdrop crossfades to whatever holds focus; the hero names it (eyebrow, title,
 * year • rating • type, overview) and the shelves open detail on CENTER. Same rows as
 * the phone home, plus the resume rail on top.
 */
@Composable
fun TvHomeTab(
    onOpenDetail: (MediaItem) -> Unit,
    onResume: (ContinueWatchingEntry) -> Unit,
) {
    val vm: HomeViewModel = viewModel()
    val state = vm.state
    val entries by vm.continueWatching.collectAsState(initial = emptyList())

    var focusedItem by remember { mutableStateOf<MediaItem?>(null) }
    var focusedEyebrow by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }
    val resumeLabel = stringResource(R.string.continue_watching)
    val trendingLabel = stringResource(R.string.trending)
    val topRatedLabel = stringResource(R.string.top_rated)

    val firstKey = when {
        entries.isNotEmpty() -> "cw:${entries.first().key}"
        state.trendingMovies.isNotEmpty() -> mediaKey(state.trendingMovies.first())
        state.trendingTv.isNotEmpty() -> mediaKey(state.trendingTv.first())
        state.topMovies.isNotEmpty() -> mediaKey(state.topMovies.first())
        state.topTv.isNotEmpty() -> mediaKey(state.topTv.first())
        else -> null
    }
    TvInitialFocus(firstFocus, key = firstKey)

    val empty = entries.isEmpty() && state.trendingMovies.isEmpty() && state.trendingTv.isEmpty() &&
        state.topMovies.isEmpty() && state.topTv.isEmpty()
    val heroItem = focusedItem
        ?: entries.firstOrNull()?.toMediaItem()
        ?: state.trendingMovies.firstOrNull()
        ?: state.trendingTv.firstOrNull()
    val heroEyebrow = focusedEyebrow ?: if (entries.isNotEmpty()) resumeLabel else trendingLabel

    val trendingMoviesTitle = stringResource(R.string.trending_movies)
    val trendingSeriesTitle = stringResource(R.string.trending_series)
    val topMoviesTitle = stringResource(R.string.top_rated_movies)
    val topSeriesTitle = stringResource(R.string.top_rated_series)

    Box(Modifier.fillMaxSize()) {
        TvBackdrop(key = heroItem) { item ->
            val url = item?.let(::heroArtwork)
            if (url != null) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        if (state.loading && empty) {
            TvLoading()
            return@Box
        }
        if (empty) {
            TvEmptyState(title = stringResource(R.string.no_results))
            return@Box
        }
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxWidth().weight(0.52f)
                    .padding(
                        start = tvContentStart(),
                        end = TvDimens.SafeHorizontal,
                        top = TvDimens.SafeVertical,
                        bottom = 8.dp,
                    ),
                verticalArrangement = Arrangement.Bottom,
            ) {
                if (heroItem != null) {
                    TvHeroText(
                        title = heroItem.title,
                        eyebrow = heroEyebrow,
                        meta = heroMeta(heroItem),
                        description = heroItem.overview.ifBlank { null },
                        descriptionLines = 3,
                    )
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(0.48f)) {
                if (entries.isNotEmpty()) {
                    item(key = "shelf-continue") {
                        TvShelf(title = resumeLabel) {
                            items(entries, key = { "cw:${it.key}" }) { entry ->
                                ContinueEntryCard(
                                    entry = entry,
                                    onResume = { onResume(entry) },
                                    modifier = focusIf("cw:${entry.key}", firstKey, firstFocus),
                                    onFocus = {
                                        focusedItem = entry.toMediaItem()
                                        focusedEyebrow = resumeLabel
                                    },
                                )
                            }
                        }
                    }
                }
                catalogShelf(
                    key = "shelf-trending-movies",
                    title = trendingMoviesTitle,
                    eyebrow = trendingLabel,
                    items = state.trendingMovies,
                    firstKey = firstKey,
                    firstFocus = firstFocus,
                    onFocus = { focusedItem = it; focusedEyebrow = trendingLabel },
                    onOpenDetail = onOpenDetail,
                )
                catalogShelf(
                    key = "shelf-trending-tv",
                    title = trendingSeriesTitle,
                    eyebrow = trendingLabel,
                    items = state.trendingTv,
                    firstKey = firstKey,
                    firstFocus = firstFocus,
                    onFocus = { focusedItem = it; focusedEyebrow = trendingLabel },
                    onOpenDetail = onOpenDetail,
                )
                catalogShelf(
                    key = "shelf-top-movies",
                    title = topMoviesTitle,
                    eyebrow = topRatedLabel,
                    items = state.topMovies,
                    firstKey = firstKey,
                    firstFocus = firstFocus,
                    onFocus = { focusedItem = it; focusedEyebrow = topRatedLabel },
                    onOpenDetail = onOpenDetail,
                )
                catalogShelf(
                    key = "shelf-top-tv",
                    title = topSeriesTitle,
                    eyebrow = topRatedLabel,
                    items = state.topTv,
                    firstKey = firstKey,
                    firstFocus = firstFocus,
                    onFocus = { focusedItem = it; focusedEyebrow = topRatedLabel },
                    onOpenDetail = onOpenDetail,
                )
            }
        }
    }
}

/** Year • rating • type line for a hero. Rating hides when TMDB has none. */
@Composable
internal fun heroMeta(item: MediaItem): List<String> {
    val meta = mutableListOf<String>()
    item.year?.let { meta += it.toString() }
    if (item.voteAverage > 0) meta += stringResource(R.string.rating_value, item.voteAverage)
    meta += typeName(item.mediaType)
    return meta
}
