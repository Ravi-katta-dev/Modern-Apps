package com.vayunmathur.youpipe.tv

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvGrid
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvLoading
import com.vayunmathur.library.tv.tvGridHeader
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.util.YouPipeViewModel
import com.vayunmathur.youpipe.util.loadRecommendations

/**
 * TV home: one scrolling 4-column grid of 16:9 cards, like the phone/tablet FeedGrid.
 *
 * Sections mirror the phone feed: Continue watching (history resume) as a header + one
 * row, then recommendations. No hero. Training actions live in the long-press options
 * dialog and the video page's visible More button — never inside cards.
 */
@Composable
fun TvHomeFeedPage(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
) {
    val recommendations by ypvm.recommendations.collectAsState()
    val recommendationsLoading by ypvm.recommendationsLoading.collectAsState()
    val history by ypvm.historyVideos.collectAsState()

    LaunchedEffect(Unit) { ypvm.loadRecommendations() }

    val historyById = remember(history) { history.associate { it.id to it.progress } }
    val videos = remember(recommendations) { recommendations.map { it.video } }
    val reasons = remember(recommendations) {
        recommendations.associate { it.video.videoID to it.reason }
    }
    val rows = rememberTvVideoRows(videos, ypvm, historyById, reasonsById = reasons)

    val continueRows = rememberTvVideoRows(
        videos = remember(history) {
            history.sortedByDescending { it.timestamp }.take(TV_CONTINUE_COUNT).map { it.videoItem }
        },
        ypvm = ypvm,
        historyProgressMsById = historyById,
    )

    var optionsFor by remember { mutableStateOf<TvVideoRow?>(null) }
    val firstFocus = remember { FocusRequester() }
    val continueTitle = stringResource(R.string.tv_shelf_continue)
    val forYouTitle = stringResource(R.string.tv_shelf_for_you)

    if (rows.isEmpty() && !recommendationsLoading) {
        TvEmptyState(
            title = stringResource(R.string.empty_recommendations),
            action = {
                TvButton(
                    text = stringResource(UiR.string.retry),
                    onClick = { ypvm.loadRecommendations() },
                )
            },
        )
        return
    }
    if (rows.isEmpty()) {
        TvLoading()
        return
    }

    TvInitialFocus(firstFocus)
    TvGrid(columns = TV_GRID_COLUMNS, modifier = Modifier.fillMaxSize()) {
        if (continueRows.isNotEmpty()) {
            tvGridHeader(
                key = "continue-header",
                title = continueTitle,
                first = true,
            )
            items(continueRows.size, key = { continueRows[it].row.videoID }) { index ->
                val item = continueRows[index]
                TvVideoCard(
                    item = item,
                    onOpen = { backStack.playVideo(item.row.videoID) },
                    onLongClick = { optionsFor = item },
                    modifier = if (index == 0) {
                        Modifier.focusRequester(firstFocus)
                    } else {
                        Modifier
                    },
                )
            }
        }
        tvGridHeader(
            key = "for-you-header",
            title = forYouTitle,
            first = continueRows.isEmpty(),
        )
        items(rows.size, key = { rows[it].row.videoID }) { index ->
            val item = rows[index]
            TvVideoCard(
                item = item,
                onOpen = { backStack.playVideo(item.row.videoID) },
                onLongClick = { optionsFor = item },
                modifier = if (continueRows.isEmpty() && index == 0) {
                    Modifier.focusRequester(firstFocus)
                } else {
                    Modifier
                },
            )
        }
    }

    optionsFor?.let { item ->
        TvVideoOptionsDialog(
            item = item,
            onDismiss = { optionsFor = null },
            onOpen = { backStack.playVideo(item.row.videoID) },
            ypvm = ypvm,
        )
    }
}

private const val TV_CONTINUE_COUNT = 4
