package com.vayunmathur.youpipe.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.ui.countString
import com.vayunmathur.youpipe.ui.videoRowState
import com.vayunmathur.youpipe.util.VideoRowState
import com.vayunmathur.youpipe.util.blockChannel
import com.vayunmathur.youpipe.util.boostChannel
import com.vayunmathur.youpipe.util.loadRecommendations
import com.vayunmathur.youpipe.util.pinChannel
import com.vayunmathur.youpipe.util.removeInterest
import com.vayunmathur.youpipe.util.SearchResultRow
import com.vayunmathur.youpipe.util.YouPipeViewModel

/**
 * TV home: the recommendation feed as a focusable grid, with a search entry row on top.
 *
 * Binder mirrors the phone `SearchPage`'s mapping (recommendations + history progress +
 * DeArrow) into plain rows — but there is no search overlay and no autofocus: search is a
 * separate [TvRoute]-less inline panel opened by explicit focus + CENTER, so the TV IME never
 * pops uninvited. Overflow actions (not-interested, pin, block) are per-row D-pad options
 * reached with RIGHT on the focused card.
 */
@Composable
fun TvHomeFeedPage(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
) {
    val recommendations by ypvm.recommendations.collectAsState()
    val recommendationsLoading by ypvm.recommendationsLoading.collectAsState()
    val history by ypvm.historyVideos.collectAsState()
    val deArrowEnabled by ypvm.deArrowEnabled.collectAsState()
    val deArrowCache by ypvm.deArrowCache.collectAsState()
    val progressById = remember(history) { history.associate { it.id to it.progress } }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        ypvm.loadRecommendations()
    }

    val rows = remember(recommendations, deArrowEnabled, deArrowCache, progressById) {
        recommendations.map { ranked ->
            val video = ranked.video
            val deArrow = if (deArrowEnabled) deArrowCache[video.videoID] else null
            val watched = progressById[video.videoID] ?: 0L
            videoRowState(
                context = context,
                videoInfo = video,
                showAuthor = true,
                reason = ranked.reason,
                percentWatched = if (video.duration > 0) {
                    (watched.toDouble() / video.duration).toFloat()
                } else {
                    0f
                },
                deArrowTitle = deArrow?.title,
                deArrowThumbnailURL = deArrow?.thumbnailUrl,
            )
        }
    }

    val gridState = rememberLazyGridState()
    val searchFocus = remember { FocusRequester() }
    TvListScreen(
        title = stringResource(R.string.label_recommendations),
        emptyMessage = if (!recommendationsLoading) {
            stringResource(R.string.empty_recommendations)
        } else {
            null
        },
        itemCount = rows.size,
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "tv-search-entry") {
                TvSearchEntryRow(
                    onOpenSearch = { backStack.add(TvRoute.Search) },
                    modifier = Modifier.focusRequester(searchFocus),
                )
            }
            items(rows, key = { it.videoID }) { row ->
                TvVideoCard(
                    row = row,
                    onOpen = { backStack.add(TvRoute.Video(row.videoID)) },
                    trailingContent = {
                        TvTrainingActionsRow(
                            row = row,
                            ypvm = ypvm,
                        )
                    },
                )
            }
        }
    }
    TvInitialFocus(searchFocus)
}

/**
 * Per-card training actions: not-interested / more-like / pin / block, same four the phone
 * `SearchPage` offers as overflow actions. Rendered as a focusable row inside the card so
 * RIGHT from the focused card lands on the first action, keeping one focus axis.
 */
@Composable
private fun TvTrainingActionsRow(
    row: VideoRowState,
    ypvm: YouPipeViewModel,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(onClick = { ypvm.removeInterest(channelKey = row.channelKey) }) {
            Text(
                text = stringResource(R.string.action_not_interested),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        Surface(onClick = { ypvm.boostChannel(row.channelKey) }) {
            Text(
                text = stringResource(R.string.action_more_like_this),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        Surface(onClick = { ypvm.pinChannel(row.channelKey) }) {
            Text(
                text = stringResource(R.string.action_pin_channel),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        Surface(onClick = { ypvm.blockChannel(row.channelKey) }) {
            Text(
                text = stringResource(R.string.action_block_channel),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * The search entry row: a focusable card that opens the search panel.
 *
 * Separate from the feed grid items so the IME only ever opens from an explicit CENTER press —
 * never from composition, scroll, or focus passing through.
 */
@Composable
private fun TvSearchEntryRow(
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.tv.material3.Surface(onClick = onOpenSearch, modifier = modifier) {
        androidx.compose.foundation.layout.Row(
            Modifier.padding(16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            com.vayunmathur.library.ui.IconSearch()
            androidx.tv.material3.Text(
                text = stringResource(com.vayunmathur.library.ui.R.string.search),
                style = androidx.tv.material3.MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}
