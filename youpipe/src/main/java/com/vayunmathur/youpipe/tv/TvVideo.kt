package com.vayunmathur.youpipe.tv

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvGrid
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvLoading
import com.vayunmathur.library.tv.tvFullWidth
import com.vayunmathur.library.tv.tvGridHeader
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.util.DownloadManager
import com.vayunmathur.youpipe.util.applyDownloadedStreams
import com.vayunmathur.youpipe.util.clearVideoError
import com.vayunmathur.youpipe.util.loadVideo
import com.vayunmathur.youpipe.util.YouPipeViewModel
import kotlinx.coroutines.flow.first

/**
 * TV video detail: two panes, no backdrop. Left (~45%): the 16:9 thumbnail card with a
 * Play/Resume overlay action, title, channel row with Subscribe, stats, and wrapped action
 * buttons (see [TvVideoLeftPane]). Right: scrolling description, chapters, related videos
 * as a 2-column grid, comments.
 *
 * Binder mirrors the phone `VideoPage` (loadVideo, downloaded streams, DeArrow, history
 * progress). The player itself opens as a pushed player route from Play — this screen is
 * browse; playback semantics live in [TvPlayer].
 *
 * **WebView degrade:** the headless `SharedWebViewRuntime` PO-token stack works on Google TV
 * but version-lags. When the system WebView is missing or too old, extraction fails every
 * load — instead of a silent stall, this screen probes once and shows the focused
 * `tv_playback_unavailable` message. quickjs and media3 are TV-safe.
 */
@Composable
fun TvVideoPage(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
    videoID: Long,
) {
    val context = LocalContext.current
    val downloadedFlow = remember(videoID) { ypvm.downloadedById(videoID) }
    val downloadedVideo by downloadedFlow.collectAsState(initial = null)
    val videoState by ypvm.videoState.collectAsState()

    // Probe once per video: missing/outdated WebView means extraction can never succeed,
    // so say so up front instead of stalling on the spinner.
    var webViewStatus by remember(videoID) { mutableStateOf<TvWebViewStatus?>(null) }
    LaunchedEffect(videoID) {
        webViewStatus = probeTvWebView(context)
        if (TvLoadedVideo.id != videoID || videoState.error) {
            TvLoadedVideo.id = videoID
            ypvm.loadVideo(videoID, downloadedFlow.first())
        }
    }
    LaunchedEffect(downloadedVideo) {
        downloadedVideo?.let { ypvm.applyDownloadedStreams(it) }
    }

    val deArrowEnabled by ypvm.deArrowEnabled.collectAsState()
    val deArrowCache by ypvm.deArrowCache.collectAsState()
    val deArrowData = if (deArrowEnabled) deArrowCache[videoID] else null
    val videoData = videoState.data?.let { data ->
        data.copy(
            title = deArrowData?.title ?: data.title,
            thumbnailURL = deArrowData?.thumbnailUrl ?: data.thumbnailURL,
        )
    }
    val videoStreams = videoState.videoStreams
    val audioStreams = videoState.audioStreams
    val comments = videoState.comments
    val relatedVideos = videoState.relatedVideos
    val chapters = videoState.segments
    val history by ypvm.historyVideos.collectAsState()
    val historyById = remember(history) { history.associate { it.id to it.progress } }
    val subscriptions by ypvm.subscriptions.collectAsState()

    var showDownloadSheet by remember { mutableStateOf(false) }
    var showDescription by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }
    val playlists by ypvm.playlists.collectAsState()
    val messenger = rememberMessenger()
    val addedToWatchLater = stringResource(R.string.tv_added_to_watch_later)
    val chaptersTitle = stringResource(R.string.tv_section_chapters)
    val relatedTitle = stringResource(R.string.label_related_videos)

    // Error / unavailable states: a focused message, never a silent spinner.
    // Downloads play locally with no PO-token, so only the streaming path is gated
    // on WebView status; a downloaded file renders regardless.
    if (videoState.error || (downloadedVideo == null && webViewStatus is TvWebViewStatus.Unavailable)) {
        val errorFocus = remember { FocusRequester() }
        TvInitialFocus(errorFocus)
        TvEmptyState(
            title = if (downloadedVideo == null && webViewStatus is TvWebViewStatus.Unavailable) {
                stringResource(R.string.tv_playback_unavailable)
            } else {
                stringResource(R.string.video_load_error)
            },
            isError = true,
            action = {
                TvButton(
                    text = stringResource(R.string.action_go_back),
                    modifier = Modifier.focusRequester(errorFocus),
                    onClick = {
                        ypvm.clearVideoError()
                        backStack.pop()
                    },
                )
            },
        )
        return
    }
    if (videoData == null) {
        TvLoading()
        return
    }

    val relatedRows = rememberTvVideoRows(relatedVideos, ypvm, historyById)
    val watchedMs = historyById[videoID] ?: 0L
    val resumeAvailable = watchedMs > 0L && videoData.duration > 0
    val existingSub = subscriptions.firstOrNull { it.channelID == videoData.authorURL }
    val optionRow = TvVideoRow(
        row = com.vayunmathur.youpipe.util.VideoRowState(
            videoID = videoID,
            title = videoData.title,
            thumbnailURL = videoData.thumbnailURL,
            author = videoData.author,
            stats = "",
            channelKey = videoData.author.lowercase(),
        ),
        info = VideoInfo(
            videoData.title, videoID, videoData.duration, videoData.views,
            videoData.uploadDate, videoData.thumbnailURL, videoData.author,
        ),
    )

    TvInitialFocus(playFocus, videoID)
    Row(Modifier.fillMaxSize()) {
        TvVideoLeftPane(
            backStack = backStack,
            ypvm = ypvm,
            videoID = videoID,
            videoData = videoData,
            deArrowThumbnail = deArrowData != null,
            watchedMs = watchedMs,
            resumeAvailable = resumeAvailable,
            existingSub = existingSub,
            videoStreamsEmpty = videoStreams.isEmpty(),
            downloadedVideo = downloadedVideo,
            playlists = playlists,
            chapters = chapters,
            messenger = messenger,
            addedToWatchLater = addedToWatchLater,
            playFocus = playFocus,
            onShowDownloadSheet = { showDownloadSheet = true },
            onShowDescription = { showDescription = true },
            onShowOptions = { showOptions = true },
            modifier = Modifier.weight(0.45f),
        )
        // Right: scrolling body — chapters, related, comments.
        TvGrid(columns = 2, modifier = Modifier.weight(0.55f).fillMaxHeight()) {
            if (chapters.isNotEmpty()) {
                tvGridHeader(
                    key = "chapters-header",
                    title = chaptersTitle,
                    first = true,
                )
                items(chapters.size, key = { chapters[it].time }) { index ->
                    TvChapterCard(
                        chapter = chapters[index],
                        onClick = { backStack.playFromDetails(videoID) },
                    )
                }
            }
            if (relatedRows.isNotEmpty()) {
                tvGridHeader(
                    key = "related-header",
                    title = relatedTitle,
                    first = chapters.isEmpty(),
                )
                items(relatedRows.size, key = { relatedRows[it].row.videoID }) { index ->
                    val item = relatedRows[index]
                    TvVideoCard(
                        item = item,
                        onOpen = { backStack.playVideo(item.row.videoID) },
                    )
                }
            }
            if (comments.isNotEmpty()) {
                tvFullWidth(key = "comments") {
                    TvVideoComments(comments)
                }
            }
        }
    }

    if (showDescription) {
        TvDescriptionDialog(videoData.title, videoData.description) { showDescription = false }
    }
    if (showOptions) {
        TvVideoOptionsDialog(
            item = optionRow,
            onDismiss = { showOptions = false },
            onOpen = { backStack.playFromDetails(videoID) },
            ypvm = ypvm,
        )
    }

    val sheetData = videoData
    if (showDownloadSheet && videoStreams.isNotEmpty()) {
        TvDownloadSheet(
            videoStreams = videoStreams,
            audioStreams = audioStreams,
            onConfirm = { videoUrl, audioUrl ->
                DownloadManager.enqueueDownload(
                    context,
                    VideoInfo(
                        sheetData.title, videoID, sheetData.duration, sheetData.views,
                        sheetData.uploadDate, sheetData.thumbnailURL, sheetData.author,
                    ),
                    videoUrl,
                    audioUrl,
                )
                showDownloadSheet = false
            },
            onDismiss = { showDownloadSheet = false },
        )
    }
}
