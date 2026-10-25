package com.vayunmathur.uniflix.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.tv.TvBackdrop
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvChip
import com.vayunmathur.library.tv.TvDimens
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvHeroText
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvLoading
import com.vayunmathur.library.tv.TvShelf
import com.vayunmathur.library.tv.tvContentStart
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.uniflix.R
import com.vayunmathur.uniflix.data.TmdbImageUrl
import com.vayunmathur.uniflix.domain.MediaType
import com.vayunmathur.uniflix.platform.DetailViewModel

/**
 * TV detail: backdrop + hero with Play and My List, season chips and an episode
 * shelf for shows, a recommendations shelf — all D-pad traversable.
 *
 * Mirrors the phone detail semantics: Play routes to the player (resolving first,
 * same as the phone), and the no-addon state links to the addon manager tab.
 */
@Composable
fun TvDetailPage(backStack: NavBackStack<TvRoute>, mediaType: String, tmdbId: Int) {
    val type = mediaTypeOf(mediaType)
    val vm: DetailViewModel = viewModel()
    LaunchedEffect(tmdbId, mediaType) { vm.load(type, tmdbId) }
    val state = vm.state
    val detail = state.detail

    val playFocus = remember { FocusRequester() }
    TvInitialFocus(playFocus, key = detail?.backdropPath)

    if (state.loading && detail == null) {
        TvLoading()
        return
    }
    if (detail == null) {
        TvEmptyState(
            title = stringResource(R.string.detail_failed),
            action = { TvButton(text = stringResource(UiR.string.back), onClick = { backStack.pop() }) },
        )
        return
    }

    val runtime = detail.runtimeMinutes?.let { stringResource(R.string.runtime_minutes, it) }
    val rating = if (detail.voteAverage > 0) {
        stringResource(R.string.rating_value, detail.voteAverage)
    } else {
        null
    }
    val meta = buildList {
        detail.year?.let { add(it.toString()) }
        rating?.let { add(it) }
        add(typeName(detail.mediaType))
        runtime?.let { add(it) }
    }
    Box(Modifier.fillMaxSize()) {
        TvBackdrop(key = detail.backdropPath) {
            val url = TmdbImageUrl.backdrop(detail.backdropPath)
            if (url != null) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "hero") {
                Column(
                    Modifier.fillMaxWidth()
                        .padding(
                            start = tvContentStart(),
                            end = TvDimens.SafeHorizontal,
                            top = TvDimens.SafeVertical + 24.dp,
                        ),
                ) {
                    TvHeroText(
                        title = detail.title,
                        meta = meta,
                        description = detail.overview.ifBlank { null },
                        descriptionLines = 3,
                        actions = {
                            TvDetailPlayButton(
                                state = state,
                                detail = detail,
                                type = type,
                                tmdbId = tmdbId,
                                mediaType = mediaType,
                                backStack = backStack,
                                vm = vm,
                                playFocus = playFocus,
                            )
                            TvButton(
                                text = stringResource(
                                    if (state.watchlisted) R.string.in_my_list else R.string.add_to_my_list,
                                ),
                                onClick = vm::toggleWatchlist,
                            )
                        },
                    )
                    if (state.providerMissing) {
                        TvButton(
                            text = stringResource(R.string.open_addon_manager),
                            onClick = {
                                backStack.pop()
                                backStack.setLast(TvRoute.Main(TV_TAB_ADDONS))
                            },
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
            if (detail.mediaType == MediaType.TV && detail.seasons.isNotEmpty()) {
                item(key = "seasons") {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        contentPadding = PaddingValues(
                            start = tvContentStart(),
                            end = TvDimens.SafeHorizontal,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            detail.seasons.filter { it.seasonNumber > 0 },
                            key = { "season-${it.seasonNumber}" },
                        ) { season ->
                            TvChip(
                                label = season.name.ifBlank {
                                    stringResource(R.string.season_name, season.seasonNumber)
                                },
                                selected = season.seasonNumber == state.selectedSeason,
                                onClick = { vm.selectSeason(type, tmdbId, season.seasonNumber) },
                            )
                        }
                    }
                }
                if (state.episodes.isNotEmpty()) {
                    item(key = "episodes") {
                        TvDetailEpisodeShelf(
                            vm = vm,
                            type = type,
                            tmdbId = tmdbId,
                            mediaType = mediaType,
                            backStack = backStack,
                        )
                    }
                }
            }
            if (detail.recommendations.isNotEmpty()) {
                item(key = "similar") {
                    TvShelf(title = stringResource(R.string.more_like_this)) {
                        items(detail.recommendations, key = ::mediaKey) { item ->
                            MediaPosterCard(
                                item = item,
                                onOpen = {
                                    backStack.add(
                                        TvRoute.Detail(item.mediaType.name.lowercase(), item.tmdbId),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
