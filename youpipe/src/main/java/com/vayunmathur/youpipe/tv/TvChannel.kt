package com.vayunmathur.youpipe.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.ui.VideoItem
import com.vayunmathur.youpipe.ui.videoRowState
import com.vayunmathur.youpipe.util.decodeHtml
import com.vayunmathur.youpipe.util.YouPipeViewModel

/**
 * TV channel page: identity header + subscribe toggle + focusable video grid.
 *
 * Mirrors the phone `ChannelPage` binding (`loadChannel`, subscriptions for membership).
 * The header and subscribe button are stacked focus targets — not a scrolling collapse —
 * because a D-pad needs a stable place to land UP from the grid.
 */
@Composable
fun TvChannelPage(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
    channelID: String,
) {
    val channelState by ypvm.channelState.collectAsState()
    val videos = channelState.videos
    val channelInfo = channelState.info
    val subscriptions by ypvm.subscriptions.collectAsState()
    val history by ypvm.historyVideos.collectAsState()
    val deArrowEnabled by ypvm.deArrowEnabled.collectAsState()
    val deArrowCache by ypvm.deArrowCache.collectAsState()
    val progressById = remember(history) { history.associate { it.id to it.progress } }
    val context = LocalContext.current
    val headerFocus = remember { FocusRequester() }

    LaunchedEffect(channelID) {
        ypvm.loadChannel(channelID)
    }

    val rows = remember(videos, deArrowEnabled, deArrowCache, progressById) {
        videos.map { video ->
            val deArrow = if (deArrowEnabled) deArrowCache[video.videoID] else null
            val watched = progressById[video.videoID] ?: 0L
            videoRowState(
                context = context,
                videoInfo = video,
                showAuthor = false,
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

    TvInitialFocus(headerFocus)
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        channelInfo?.let { info ->
            androidx.compose.foundation.layout.Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                if (info.avatar.isNotEmpty()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(info.avatar)
                            .memoryCacheKey("channel-avatar-${info.channelID}")
                            .build(),
                        contentDescription = null,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(text = info.name.decodeHtml(), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = stringResource(
                            R.string.subscribers_count,
                            com.vayunmathur.youpipe.ui.countString(context, info.subscribers),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val existing = subscriptions.firstOrNull { it.channelID == info.channelID }
            Button(
                onClick = {
                    if (existing == null) {
                        ypvm.upsertSubscription(
                            Subscription(name = info.name, channelID = info.channelID, avatarURL = info.avatar),
                        )
                    } else {
                        ypvm.deleteSubscription(existing)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .focusRequester(headerFocus),
            ) {
                Text(
                    if (existing == null) {
                        stringResource(R.string.action_subscribe)
                    } else {
                        stringResource(R.string.action_unsubscribe)
                    },
                )
            }
        }
        val gridState = rememberLazyGridState()
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(rows, key = { it.videoID }) { row ->
                TvVideoCard(
                    row = row,
                    onOpen = { backStack.add(TvRoute.Video(row.videoID)) },
                )
            }
        }
    }
}
