package com.vayunmathur.youpipe.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.tv.TvChip
import com.vayunmathur.library.tv.TvDimens
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvGrid
import com.vayunmathur.library.tv.TvInfoCard
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvLoading
import com.vayunmathur.library.tv.TvTextField
import com.vayunmathur.library.tv.tvContentStart
import com.vayunmathur.library.tv.tvGridHeader
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.ui.ChannelInfo
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.ui.countString
import com.vayunmathur.youpipe.util.YouPipeViewModel

/**
 * TV search: a [TvTextField] (IME only on CENTER) + suggestions/history chips + results
 * grid. Videos open the detail; channels open the channel page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TvSearchPage(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
) {
    val query by ypvm.searchQuery.collectAsState()
    val suggestions by ypvm.suggestions.collectAsState()
    val results by ypvm.searchResults.collectAsState()
    val history by ypvm.historyVideos.collectAsState()
    val historyById = remember(history) { history.associate { it.id to it.progress } }

    var fieldText by remember(query) { mutableStateOf(query) }
    var submitted by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }

    fun submit(text: String) {
        fieldText = text
        ypvm.setSearchQuery(text)
        val watchID = ypvm.resolveWatchUrl()
        if (watchID != null) {
            backStack.playVideo(watchID)
        } else {
            ypvm.performSearch()
            submitted = true
        }
    }

    TvInitialFocus(fieldFocus, submitted)
    Column(Modifier.fillMaxSize()) {
        TvTextField(
            value = fieldText,
            onValueChange = {
                fieldText = it
                ypvm.setSearchQuery(it)
                submitted = false
            },
            placeholder = stringResource(UiR.string.search),
            leading = { IconSearch() },
            onSubmit = { submit(fieldText) },
            imeAction = ImeAction.Search,
            focusRequester = fieldFocus,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = tvContentStart(), end = TvDimens.SafeHorizontal, top = TvDimens.SafeVertical),
        )
        val videoResults = remember(results) { results.filterIsInstance<VideoInfo>() }
        val channelResults = remember(results) { results.filterIsInstance<ChannelInfo>() }
        val rows = rememberTvVideoRows(videoResults, ypvm, historyById)
        val channelsTitle = stringResource(R.string.tv_section_channels)
        val videosTitle = stringResource(R.string.tv_section_videos)

        when {
            results.isNotEmpty() -> {
                TvGrid(columns = TV_GRID_COLUMNS, modifier = Modifier.fillMaxSize()) {
                    if (channelResults.isNotEmpty()) {
                        tvGridHeader(
                            key = "channels-header",
                            title = channelsTitle,
                            first = true,
                        )
                    }
                    items(
                        channelResults.size,
                        key = { "c-${channelResults[it].channelID}" },
                        span = { GridItemSpan(2) },
                    ) { index ->
                        val channel = channelResults[index]
                        TvChannelSearchCard(
                            channel = channel,
                            onClick = { backStack.add(TvRoute.Channel(channel.channelID)) },
                        )
                    }
                    if (videoResults.isNotEmpty()) {
                        tvGridHeader(
                            key = "videos-header",
                            title = videosTitle,
                            first = channelResults.isEmpty(),
                        )
                    }
                    items(rows.size, key = { rows[it].row.videoID }) { index ->
                        val item = rows[index]
                        TvVideoCard(
                            item = item,
                            onOpen = { backStack.playVideo(item.row.videoID) },
                        )
                    }
                }
            }
            submitted -> TvLoading()
            suggestions.isNotEmpty() -> {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = tvContentStart(), end = TvDimens.SafeHorizontal, top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    suggestions.forEach { suggestion ->
                        TvChip(
                            label = suggestion,
                            selected = false,
                            onClick = { submit(suggestion) },
                        )
                    }
                }
            }
            else -> TvEmptyState(title = stringResource(R.string.tv_search_hint), icon = { IconSearch() })
        }
    }
}

/** One channel result: compact card with a circular avatar, name and subscriber count. */
@Composable
private fun TvChannelSearchCard(
    channel: ChannelInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    TvInfoCard(
        title = channel.name,
        onClick = onClick,
        modifier = modifier,
        subtitle = channelSubtitle(channel),
        leading = {
            if (channel.avatar.isNotEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(channel.avatar)
                        .memoryCacheKey("channel-avatar-${channel.channelID}")
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            }
        },
        leadingSize = 56.dp,
    )
}

/** Subscriber-count subtitle for a channel result. */
@Composable
private fun channelSubtitle(channel: ChannelInfo): String {
    val context = LocalContext.current
    return stringResource(R.string.subscribers_count, countString(context, channel.subscribers))
}
