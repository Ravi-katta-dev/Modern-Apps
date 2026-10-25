package com.vayunmathur.youpipe.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.tv.TvChip
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvLoading
import com.vayunmathur.library.tv.TvRowCard
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.tv.TvTextField
import com.vayunmathur.library.tv.tvContentPadding
import com.vayunmathur.library.ui.IconPerson
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
 * grid. Videos and channels both resolve: videos open the detail, channels open the
 * channel page.
 */
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
            backStack.add(TvRoute.Video(watchID))
        } else {
            ypvm.performSearch()
            submitted = true
        }
    }

    TvInitialFocus(fieldFocus, submitted)
    Column(Modifier.fillMaxSize()) {
        TvScreenHeader(title = stringResource(UiR.string.search))
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
            modifier = Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 8.dp),
        )
        val videoResults = remember(results) { results.filterIsInstance<VideoInfo>() }
        val channelResults = remember(results) { results.filterIsInstance<ChannelInfo>() }
        val rows = rememberTvVideoRows(videoResults, ypvm, historyById)

        when {
            results.isNotEmpty() -> {
                if (channelResults.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(
                            start = 48.dp,
                            end = 48.dp,
                            top = 8.dp,
                            bottom = 8.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(channelResults, key = { "c-${it.channelID}" }) { channel ->
                            TvRowCard(
                                title = channel.name,
                                subtitle = channelSubtitle(channel),
                                leading = { IconPerson() },
                                width = 340.dp,
                                onClick = { backStack.add(TvRoute.Channel(channel.channelID)) },
                            )
                        }
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(TV_GRID_COLUMNS),
                    state = rememberLazyGridState(),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = tvContentPadding(),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    items(rows.size, key = { rows[it].row.videoID }) { index ->
                        val item = rows[index]
                        TvVideoCard(
                            item = item,
                            onOpen = { backStack.add(TvRoute.Video(item.row.videoID)) },
                        )
                    }
                }
            }
            submitted -> TvLoading()
            suggestions.isNotEmpty() -> {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = 48.dp,
                        end = 48.dp,
                        top = 8.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(suggestions, key = { it }) { suggestion ->
                        TvChip(
                            label = suggestion,
                            selected = false,
                            onClick = { submit(suggestion) },
                        )
                    }
                }
            }
            else -> TvEmptyState(title = stringResource(UiR.string.search))
        }
    }
}

/** Subscriber-count subtitle for a channel row. */
@Composable
private fun channelSubtitle(channel: ChannelInfo): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    return stringResource(R.string.subscribers_count, countString(context, channel.subscribers))
}
