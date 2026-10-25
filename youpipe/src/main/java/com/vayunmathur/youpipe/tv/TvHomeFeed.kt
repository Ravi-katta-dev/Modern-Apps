package com.vayunmathur.youpipe.tv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.tv.TvBackdrop
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvDialog
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvHeroText
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvListRow
import com.vayunmathur.library.tv.TvLoading
import com.vayunmathur.library.tv.TvShelf
import com.vayunmathur.library.tv.tvContentStart
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.util.YouPipeViewModel
import com.vayunmathur.youpipe.util.blockChannel
import com.vayunmathur.youpipe.util.boostChannel
import com.vayunmathur.youpipe.util.loadRecommendations
import com.vayunmathur.youpipe.util.pinChannel
import com.vayunmathur.youpipe.util.removeInterest

/**
 * TV home: an immersive hero for the focused video above shelves of 16:9 cards.
 *
 * The hero carries the recommendation reason as its eyebrow, with Play/Resume as the one
 * primary action and "More like this" / "Not interested" / "More" as secondary buttons.
 * Training actions live here and in the options dialog — never as buttons stacked inside
 * every card. Continuing content gets its own "Continue watching" shelf from history.
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
    // Latest uploads across subscriptions — the same feed the subscription screen reads,
    // trimmed to one shelf here.
    val latestSubs by remember { ypvm.subscriptionVideosFor(null) }
        .collectAsState(initial = emptyList())
    val latestSubRows = rememberTvVideoRows(
        videos = remember(latestSubs) { latestSubs.take(TV_LATEST_SUBS_COUNT) },
        ypvm = ypvm,
        historyProgressMsById = historyById,
    )

    var focusedId by remember { mutableStateOf<Long?>(null) }
    var optionsFor by remember { mutableStateOf<TvVideoRow?>(null) }
    val heroItem = remember(rows, continueRows, latestSubRows, focusedId) {
        sequenceOf(rows, continueRows, latestSubRows).flatten().firstOrNull { it.row.videoID == focusedId }
            ?: rows.firstOrNull()
    }
    val playFocus = remember { FocusRequester() }

    if (rows.isEmpty() && !recommendationsLoading) {
        TvEmptyState(
            title = stringResource(R.string.empty_recommendations),
            action = {
                TvButton(
                    text = stringResource(com.vayunmathur.library.ui.R.string.retry),
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

    // Keyed on content arriving, not on the hero: re-keying on the hero would yank focus back
    // to Play every time a card is focused.
    TvInitialFocus(playFocus)
    Box(Modifier.fillMaxSize()) {
        heroItem?.let { hero ->
            TvBackdrop(key = hero.row.videoID) { _ ->
                if (hero.row.thumbnailURL.isNotEmpty()) {
                    val context = LocalContext.current
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(hero.row.thumbnailURL)
                            .memoryCacheKey(
                                "video-thumb-${hero.row.videoID}-" +
                                    if (hero.row.deArrowThumbnail) "da" else "yt",
                            )
                            .build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            heroItem?.let { hero ->
                Column(Modifier.padding(start = tvContentStart(), top = 56.dp, bottom = 28.dp)) {
                    TvHeroText(
                        title = hero.row.title,
                        eyebrow = hero.row.reason?.let {
                            LocalContext.current.getString(R.string.recommendation_reason, it)
                        },
                        meta = listOfNotNull(
                            hero.row.author,
                            hero.row.stats.takeIf { it.isNotBlank() },
                        ),
                        actions = {
                            val watched = historyById[hero.row.videoID] ?: 0L
                            TvButton(
                                text = stringResource(
                                    if (watched > 0L) R.string.tv_action_resume
                                    else com.vayunmathur.library.ui.R.string.play,
                                ),
                                onClick = { backStack.add(TvRoute.Video(hero.row.videoID)) },
                                icon = { IconPlay() },
                                primary = true,
                                modifier = Modifier.focusRequester(playFocus),
                            )
                            TvButton(
                                text = stringResource(R.string.action_more_like_this),
                                onClick = { ypvm.boostChannel(hero.row.channelKey) },
                            )
                            TvButton(
                                text = stringResource(R.string.action_not_interested),
                                onClick = { ypvm.removeInterest(channelKey = hero.row.channelKey) },
                            )
                            TvButton(
                                text = stringResource(R.string.tv_action_video_options),
                                onClick = { optionsFor = hero },
                                icon = { IconMoreVert() },
                            )
                        },
                    )
                }
            }
            if (continueRows.isNotEmpty()) {
                TvShelf(title = stringResource(R.string.tv_shelf_continue)) {
                    items(continueRows.size, key = { continueRows[it].row.videoID }) { index ->
                        val item = continueRows[index]
                        TvVideoCard(
                            item = item,
                            onOpen = { backStack.add(TvRoute.Video(item.row.videoID)) },
                            onFocus = { focusedId = item.row.videoID },
                            onLongClick = { optionsFor = item },
                        )
                    }
                }
            }
            if (latestSubRows.isNotEmpty()) {
                TvShelf(title = stringResource(R.string.tv_shelf_latest_subs)) {
                    items(latestSubRows.size, key = { latestSubRows[it].row.videoID }) { index ->
                        val item = latestSubRows[index]
                        TvVideoCard(
                            item = item,
                            onOpen = { backStack.add(TvRoute.Video(item.row.videoID)) },
                            onFocus = { focusedId = item.row.videoID },
                            onLongClick = { optionsFor = item },
                        )
                    }
                }
            }
            TvShelf(title = stringResource(R.string.tv_shelf_for_you)) {
                items(rows.size, key = { rows[it].row.videoID }) { index ->
                    val item = rows[index]
                    TvVideoCard(
                        item = item,
                        onOpen = { backStack.add(TvRoute.Video(item.row.videoID)) },
                        onFocus = { focusedId = item.row.videoID },
                        onLongClick = { optionsFor = item },
                    )
                }
            }
        }
    }

    optionsFor?.let { item ->
        TvVideoOptionsDialog(
            item = item,
            onDismiss = { optionsFor = null },
            onOpen = { backStack.add(TvRoute.Video(item.row.videoID)) },
            ypvm = ypvm,
        )
    }
}

/**
 * The recommendation-training dialog: not-interested / more-like / pin / block for one
 * video's channel. Opened via long-press AND the hero's visible "More" button — never
 * buttons stacked inside every card.
 */
@Composable
internal fun TvVideoOptionsDialog(
    item: TvVideoRow,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    ypvm: YouPipeViewModel,
) {
    TvDialog(
        title = item.row.title,
        onDismissRequest = onDismiss,
        body = listOfNotNull(item.row.author, item.row.stats.takeIf { it.isNotBlank() })
            .joinToString("  •  ").takeIf { it.isNotBlank() },
        buttons = {
            TvButton(text = stringResource(com.vayunmathur.library.ui.R.string.play), onClick = {
                onDismiss()
                onOpen()
            }, primary = true)
            TvButton(
                text = stringResource(com.vayunmathur.library.ui.R.string.close),
                onClick = onDismiss,
            )
        },
        content = {
            TvOptionsList(item, onDismiss, ypvm)
        },
    )
}

/** The four training rows of the video options dialog. Split for FileLength. */
@Composable
private fun TvOptionsList(
    item: TvVideoRow,
    onDismiss: () -> Unit,
    ypvm: YouPipeViewModel,
) {
    Column {
        TvListRow(
            headline = stringResource(R.string.action_not_interested),
            supporting = item.row.reason?.let {
                LocalContext.current.getString(R.string.recommendation_reason, it)
            },
            onClick = {
                ypvm.removeInterest(channelKey = item.row.channelKey)
                onDismiss()
            },
        )
        TvListRow(
            headline = stringResource(R.string.action_more_like_this),
            onClick = {
                ypvm.boostChannel(item.row.channelKey)
                onDismiss()
            },
        )
        TvListRow(
            headline = stringResource(R.string.action_pin_channel),
            onClick = {
                ypvm.pinChannel(item.row.channelKey)
                onDismiss()
            },
        )
        TvListRow(
            headline = stringResource(R.string.action_block_channel),
            onClick = {
                ypvm.blockChannel(item.row.channelKey)
                onDismiss()
            },
        )
    }
}

private const val TV_CONTINUE_COUNT = 10
private const val TV_LATEST_SUBS_COUNT = 12
