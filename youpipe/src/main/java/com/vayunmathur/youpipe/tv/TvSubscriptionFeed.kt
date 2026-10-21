package com.vayunmathur.youpipe.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.vayunmathur.library.ui.CircularProgressIndicator
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
    // Same loading contract as the phone SubscriptionVideosPage: fetchProgress bar while the
    // worker runs, skeleton placeholders before the first DB emission. The notification-style
    // progress toggle is phone-only (notification UI) and stays unwired on TV.
    val fetchProgress by ypvm.fetchProgress.collectAsState()
    val hasLoaded by ypvm.hasLoadedSubscriptionVideos.collectAsState()
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
        emptyMessage = if (hasLoaded) empty else null,
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
            if (fetchProgress in 0f..1f) {
                item(key = "fetch-progress", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                    Box(Modifier.fillMaxWidth().padding(16.dp)) {
                        CircularProgressIndicator(progress = { fetchProgress })
                    }
                }
            }
            if (!hasLoaded) {
                // Skeleton placeholders before the first DB emission, so the screen
                // never looks frozen while the feed is loading.
                items(TV_SUBSCRIPTION_SKELETON_COUNT, key = { "skeleton-$it" }) {
                    TvSubscriptionSkeletonCard()
                }
            } else {
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
}

private const val TV_SUBSCRIPTION_SKELETON_COUNT = 6

/**
 * One placeholder card matching [TvVideoCard]'s layout: a 16:9 block plus stacked gray
 * bars. Same static `surfaceVariant` look as the phone `VideoRowSkeleton`.
 */
@Composable
private fun TvSubscriptionSkeletonCard(modifier: Modifier = Modifier) {
    Column(modifier.padding(12.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(16.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth(0.6f)
                .height(12.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}
