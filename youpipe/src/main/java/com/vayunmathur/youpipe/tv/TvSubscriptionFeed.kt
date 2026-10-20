package com.vayunmathur.youpipe.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.ui.videoRowState
import com.vayunmathur.youpipe.util.YouPipeViewModel

/**
 * TV subscription feed: newest uploads across every channel (or one category) as a
 * focusable grid.
 *
 * Same binder mapping as the phone `SubscriptionVideosPage` (feed + history progress +
 * DeArrow → rows); the grid is fixed 3-column rather than adaptive — a TV reports one
 * window size and `isExpandedWidth` answers for a phone, not a panel.
 */
@Composable
fun TvSubscriptionFeedPage(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
    category: String?,
) {
    val videos by remember(category) { ypvm.subscriptionVideosFor(category) }
        .collectAsState(initial = emptyList())
    val history by ypvm.historyVideos.collectAsState()
    val deArrowEnabled by ypvm.deArrowEnabled.collectAsState()
    val deArrowCache by ypvm.deArrowCache.collectAsState()
    val progressById = remember(history) { history.associate { it.id to it.progress } }
    val context = LocalContext.current
    val firstFocus = remember { FocusRequester() }

    val rows = remember(videos, deArrowEnabled, deArrowCache, progressById) {
        videos.map { video ->
            val deArrow = if (deArrowEnabled) deArrowCache[video.videoID] else null
            val watched = progressById[video.videoID] ?: 0L
            videoRowState(
                context = context,
                videoInfo = video,
                showAuthor = true,
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

    TvInitialFocus(firstFocus)
    val gridState = rememberLazyGridState()
    val title = category ?: stringResource(R.string.label_all_subscriptions)
    val empty = stringResource(R.string.empty_recommendations)
    TvListScreen(
        title = title,
        itemCount = rows.size,
        emptyMessage = empty,
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
            items(rows, key = { it.videoID }) { row ->
                val index = rows.indexOf(row)
                TvVideoCard(
                    row = row,
                    onOpen = { backStack.add(TvRoute.Video(row.videoID)) },
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
    }
}
