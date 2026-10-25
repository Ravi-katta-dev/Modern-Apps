package com.vayunmathur.youpipe.tv

import android.webkit.WebView
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.tv.TvBackdrop
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvHeroText
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvLoading
import com.vayunmathur.library.tv.TvShelf
import com.vayunmathur.library.tv.tvContentStart
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconInfo
import com.vayunmathur.library.ui.IconList
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.IconSchedule
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.util.DownloadManager
import com.vayunmathur.youpipe.util.applyDownloadedStreams
import com.vayunmathur.youpipe.util.clearVideoError
import com.vayunmathur.youpipe.util.loadVideo
import com.vayunmathur.youpipe.util.videoIDtoURL
import com.vayunmathur.youpipe.util.YouPipeViewModel
import kotlinx.coroutines.flow.first

/**
 * TV video detail: full-bleed thumbnail backdrop + hero (title, channel, stats, 3-line
 * description) with one primary Play/Resume action and secondary Save / Watch later /
 * Download / Subscribe / Channel actions; chapters shelf; related shelf.
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
    val resources = LocalResources.current
    val downloadedFlow = remember(videoID) { ypvm.downloadedById(videoID) }
    val downloadedVideo by downloadedFlow.collectAsState(initial = null)
    val videoState by ypvm.videoState.collectAsState()

    // Probe once per video: missing/outdated WebView means extraction can never succeed,
    // so say so up front instead of stalling on the spinner.
    var webViewStatus by remember(videoID) { mutableStateOf<TvWebViewStatus?>(null) }
    LaunchedEffect(videoID) {
        webViewStatus = probeTvWebView(context)
        val downloaded = downloadedFlow.first()
        ypvm.loadVideo(videoID, downloaded)
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
    val playFocus = remember { FocusRequester() }
    val playlists by ypvm.playlists.collectAsState()
    val messenger = rememberMessenger()
    val addedToWatchLater = stringResource(R.string.tv_added_to_watch_later)

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

    TvInitialFocus(playFocus, videoID)
    Box(Modifier.fillMaxSize()) {
        TvBackdrop(key = videoID) {
            if (videoData.thumbnailURL.isNotEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(videoData.thumbnailURL)
                        .memoryCacheKey(
                            "video-thumb-$videoID-" + if (deArrowData != null) "da" else "yt",
                        )
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(start = tvContentStart(), top = 64.dp, bottom = 24.dp)) {
                TvHeroText(
                    title = videoData.title,
                    meta = listOf(
                        if (videoData.views < 0) {
                            // Paid-content branch mirrors the phone VideoPage byline.
                            resources.getString(
                                R.string.video_info_paid_format,
                                videoData.author,
                                com.vayunmathur.youpipe.ui.uploadTimeAgo(context, videoData.uploadDate),
                            )
                        } else {
                            resources.getString(
                                R.string.video_info_format,
                                videoData.author,
                                com.vayunmathur.youpipe.ui.countString(context, videoData.views),
                                com.vayunmathur.youpipe.ui.uploadTimeAgo(context, videoData.uploadDate),
                            )
                        },
                    ),
                    description = videoData.description,
                    descriptionLines = 3,
                    actions = {
                        TvButton(
                            text = stringResource(
                                if (resumeAvailable) R.string.tv_action_resume
                                else UiR.string.play,
                            ),
                            onClick = {
                                backStack.add(
                                    TvRoute.VideoPlayerRoute(videoID, resumeAvailable),
                                )
                            },
                            icon = { IconPlay() },
                            primary = true,
                            modifier = Modifier.focusRequester(playFocus),
                        )
                        TvButton(
                            text = stringResource(R.string.add_to_playlist),
                            onClick = { backStack.add(TvRoute.AddToPlaylist(videoID, includeWatchLater = false)) },
                            icon = { IconList() },
                        )
                        TvButton(
                            text = stringResource(R.string.playlist_watch_later),
                            onClick = {
                                playlists.firstOrNull { it.mandatory }?.let { watchLater ->
                                    ypvm.playlistOps.addVideoToPlaylist(
                                        watchLater.id,
                                        VideoInfo(
                                            videoData.title, videoID, videoData.duration, videoData.views,
                                            videoData.uploadDate, videoData.thumbnailURL, videoData.author,
                                        ),
                                    )
                                    messenger.show(addedToWatchLater)
                                }
                            },
                            icon = { IconSchedule() },
                        )
                        if (downloadedVideo != null) {
                            TvButton(
                                text = stringResource(UiR.string.delete),
                                onClick = { downloadedVideo?.let { ypvm.deleteDownloadedVideo(it) } },
                                icon = { IconDownload() },
                            )
                        } else if (videoStreams.isNotEmpty()) {
                            TvButton(
                                text = stringResource(R.string.download),
                                onClick = { showDownloadSheet = true },
                                icon = { IconDownload() },
                            )
                        }
                        TvButton(
                            text = stringResource(
                                if (existingSub == null) R.string.action_subscribe
                                else R.string.action_unsubscribe,
                            ),
                            onClick = {
                                if (existingSub == null) {
                                    ypvm.upsertSubscription(
                                        Subscription(
                                            name = videoData.author,
                                            channelID = videoData.authorURL,
                                            avatarURL = videoData.authorThumbnail,
                                        ),
                                    )
                                } else {
                                    ypvm.deleteSubscription(existingSub)
                                }
                            },
                        )
                        TvButton(
                            text = stringResource(R.string.label_channels),
                            onClick = { backStack.add(TvRoute.Channel(videoData.authorURL)) },
                        )
                        TvButton(
                            text = stringResource(R.string.label_description),
                            onClick = { showDescription = true },
                            icon = { IconInfo() },
                        )
                        TvButton(
                            text = stringResource(UiR.string.share),
                            onClick = { ExternalIntents.shareText(context, videoIDtoURL(videoID)) },
                            icon = { IconShare() },
                        )
                    },
                )
            }
            if (chapters.isNotEmpty()) {
                TvShelf(title = stringResource(R.string.chapter)) {
                    items(chapters.size, key = { chapters[it].time }) { index ->
                        TvChapterCard(
                            chapter = chapters[index],
                            onClick = {
                                // Chapters seek inside playback: open the player at this video;
                                // the player overlay's chapter sheet seeks from there.
                                backStack.add(TvRoute.VideoPlayerRoute(videoID, false))
                            },
                        )
                    }
                }
            }
            if (relatedRows.isNotEmpty()) {
                TvShelf(title = stringResource(R.string.label_related_videos)) {
                    items(relatedRows.size, key = { relatedRows[it].row.videoID }) { index ->
                        val item = relatedRows[index]
                        TvVideoCard(
                            item = item,
                            onOpen = { backStack.add(TvRoute.Video(item.row.videoID)) },
                        )
                    }
                }
            }
            if (comments.isNotEmpty()) TvVideoComments(comments)
        }
    }

    if (showDescription) {
        TvDescriptionDialog(videoData.title, videoData.description) { showDescription = false }
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

