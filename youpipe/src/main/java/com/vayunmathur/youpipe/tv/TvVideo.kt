package com.vayunmathur.youpipe.tv

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.IconThumbDown
import com.vayunmathur.library.ui.IconThumbUp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.platform.CastPlayback
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.ui.Comment
import com.vayunmathur.youpipe.ui.LinkifiedText
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.ui.videoRowState
import com.vayunmathur.youpipe.util.applyDownloadedStreams
import com.vayunmathur.youpipe.util.clearVideoError
import com.vayunmathur.youpipe.util.DownloadManager
import com.vayunmathur.youpipe.util.loadVideo
import com.vayunmathur.youpipe.util.VideoDetailActions
import com.vayunmathur.youpipe.util.VideoDetailUiState
import com.vayunmathur.youpipe.util.videoIDtoURL
import com.vayunmathur.youpipe.util.YouPipeViewModel
import kotlinx.coroutines.flow.first

/**
 * TV video screen: player on top, details + related below, all D-pad traversable.
 *
 * Binder mirrors the phone `VideoPage` (loadVideo, downloaded streams, DeArrow, history
 * progress, next/previous via `CastPlayback.onNavigate`) — but fullscreen/orientation plumbing
 * is gone (landscape-locked manifest), comments/description tabs are a single scroll, and the
 * error state is a focused message with a back row rather than a dialog.
 *
 * **WebView degrade:** the headless `SharedWebViewRuntime` PO-token stack works on Google TV
 * but version-lags. When the system WebView is missing or too old, extraction fails every
 * load — instead of a silent stall, this screen probes once and shows the focused
 * `tv_playback_unavailable` message with a back row. quickjs and media3 are TV-safe.
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
    val subtitles = videoState.subtitles
    val segments = videoState.segments
    // Comments: the data is already fetched by loadVideo (same VideoState.comments the
    // phone's CommentsSection reads) — this screen only renders it.
    val comments = videoState.comments
    val relatedVideos = videoState.relatedVideos
    val history by ypvm.historyVideos.collectAsState()
    val progressById = remember(history) { history.associate { it.id to it.progress } }
    val activeDownloads by DownloadManager.activeDownloads.collectAsState()

    // Next/previous for the remote: same contract as the phone — next is the first related
    // video, previous is the way back. Installed here so [TvDpadInput] can drive it.
    val canGoNext = relatedVideos.isNotEmpty()
    val canGoPrevious = backStack.backStack.count { it is TvRoute.Video } > 1
    LaunchedEffect(canGoNext, canGoPrevious) {
        CastPlayback.update { it.copy(hasNext = canGoNext, hasPrevious = canGoPrevious) }
    }
    DisposableEffect(relatedVideos, canGoPrevious) {
        val handler: (Boolean) -> Unit = { next ->
            if (next) {
                relatedVideos.firstOrNull()?.let { backStack.add(TvRoute.Video(it.videoID)) }
            } else if (canGoPrevious) {
                backStack.pop()
            }
        }
        CastPlayback.onNavigate = handler
        onDispose { if (CastPlayback.onNavigate === handler) CastPlayback.onNavigate = null }
    }

    val errorFocus = remember { FocusRequester() }
    var showDownloadSheet by remember { mutableStateOf(false) }

    // Error / unavailable states: a focused message, never a silent spinner.
    // Downloads play locally with no PO-token, so only the streaming path is gated
    // on WebView status; a downloaded file renders regardless.
    if (videoState.error || (downloadedVideo == null && webViewStatus is TvWebViewStatus.Unavailable)) {
        TvInitialFocus(errorFocus)
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = if (downloadedVideo == null && webViewStatus is TvWebViewStatus.Unavailable) {
                    stringResource(R.string.tv_playback_unavailable)
                } else {
                    stringResource(R.string.video_load_error)
                },
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
            Button(
                onClick = {
                    ypvm.clearVideoError()
                    backStack.pop()
                },
                modifier = Modifier.focusRequester(errorFocus),
            ) {
                Text(stringResource(R.string.action_go_back))
            }
        }
        return
    }

    val relatedRows = remember(relatedVideos, deArrowEnabled, deArrowCache, progressById) {
        relatedVideos.map { related ->
            val relatedDeArrow = if (deArrowEnabled) deArrowCache[related.videoID] else null
            val watched = progressById[related.videoID] ?: 0L
            videoRowState(
                context = context,
                videoInfo = related,
                showAuthor = true,
                percentWatched = if (related.duration > 0) {
                    (watched.toDouble() / related.duration).toFloat()
                } else {
                    0f
                },
                deArrowTitle = relatedDeArrow?.title,
                deArrowThumbnailURL = relatedDeArrow?.thumbnailUrl,
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        videoData?.let { data ->
            if (videoStreams.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().weight(0.62f)) {
                    TvPlayer(
                        ypvm = ypvm,
                        videoInfo = VideoInfo(
                            data.title, videoID, data.duration, data.views,
                            data.uploadDate, data.thumbnailURL, data.author,
                        ),
                        videoStreams = videoStreams,
                        audioStreams = audioStreams,
                        subtitles = subtitles,
                        segments = segments,
                    )
                }
            }
        }
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .weight(0.38f)
                .padding(horizontal = 48.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            videoData?.let { data ->
                item(key = "title") {
                    Text(text = data.title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                }
                item(key = "byline") {
                    Text(
                        // Paid-content branch mirrors the phone VideoPage byline: views < 0
                        // means paid, so the byline uses video_info_paid_format instead.
                        text = if (data.views < 0) {
                            resources.getString(
                                R.string.video_info_paid_format,
                                data.author,
                                com.vayunmathur.youpipe.ui.uploadTimeAgo(context, data.uploadDate),
                            )
                        } else {
                            resources.getString(
                                R.string.video_info_format,
                                data.author,
                                com.vayunmathur.youpipe.ui.countString(context, data.views),
                                com.vayunmathur.youpipe.ui.uploadTimeAgo(context, data.uploadDate),
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
                item(key = "video-actions") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(
                            onClick = { backStack.add(TvRoute.Channel(data.authorURL)) },
                        ) {
                            Text(
                                text = stringResource(R.string.label_channels),
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                        if (downloadedVideo != null) {
                            Surface(onClick = { downloadedVideo?.let { ypvm.deleteDownloadedVideo(it) } }) {
                                Text(
                                    text = stringResource(R.string.action_delete_playlist),
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        } else if (videoStreams.isNotEmpty()) {
                            Surface(onClick = { showDownloadSheet = true }) {
                                Row(Modifier.padding(12.dp)) {
                                    IconDownload()
                                }
                            }
                        }
                        Surface(onClick = { backStack.add(TvRoute.AddToPlaylist(videoID)) }) {
                            Text(
                                text = stringResource(R.string.add_to_playlist),
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                        Surface(
                            onClick = {
                                ExternalIntents.shareText(context, videoIDtoURL(videoID))
                            },
                        ) {
                            Row(Modifier.padding(12.dp)) { IconShare() }
                        }
                    }
                }
                item(key = "description") {
                    LinkifiedText(
                        text = data.description,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = Color.White.copy(alpha = 0.8f),
                        ),
                    )
                }
                if (comments.isNotEmpty()) {
                    item(key = "comments-header") {
                        Text(
                            text = stringResource(R.string.label_comments),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                        )
                    }
                    items(comments, key = { "${it.author}|${it.text.hashCode()}" }) { comment ->
                        TvCommentRow(comment = comment)
                    }
                }
            }
            items(relatedRows, key = { it.videoID }) { row ->
                TvVideoCard(
                    row = row,
                    onOpen = { backStack.add(TvRoute.Video(row.videoID)) },
                )
            }
        }
    }

    val sheetData = videoData
    if (showDownloadSheet && sheetData != null && videoStreams.isNotEmpty()) {
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

/** Whether the system WebView can mint PO tokens on this TV box. */
private sealed interface TvWebViewStatus {
    data object Ready : TvWebViewStatus
    data class Unavailable(val reason: String) : TvWebViewStatus
}

/**
 * One TV comment row: author + likes line, linkified body. Mirrors the phone
 * `ui/VideoSections.kt` CommentItem without the phone-only ListItem chrome — a plain
 * column in the TV detail list, reachable by DOWN rather than a tab.
 */
@Composable
private fun TvCommentRow(
    comment: Comment,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = comment.author,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconThumbUp(Modifier.size(16.dp))
            Text(
                text = comment.likes.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 6.dp),
            )
            IconThumbDown(Modifier.size(16.dp).padding(start = 16.dp))
        }
        LinkifiedText(
            text = comment.text,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = Color.White.copy(alpha = 0.8f),
            ),
        )
    }
}

/**
 * Probe the system WebView once: missing package or a version too old to run the attestation
 * JS means every load would fail — surface it as a message, not a stall.
 */
private fun probeTvWebView(context: android.content.Context): TvWebViewStatus {
    return try {
        val info = WebView.getCurrentWebViewPackage() ?: return TvWebViewStatus.Unavailable("missing")
        // Attestation JS needs a reasonably current Chromium; boxes that lag years behind
        // cannot mint PO tokens. 100 is a floor, not a target — current is 120+.
        val major = info.versionName?.substringBefore('.')?.toIntOrNull() ?: 0
        if (major < MIN_TV_WEBVIEW_MAJOR) {
            TvWebViewStatus.Unavailable("outdated:${info.versionName}")
        } else {
            TvWebViewStatus.Ready
        }
    } catch (_: Exception) {
        TvWebViewStatus.Unavailable("missing")
    }
}

private const val MIN_TV_WEBVIEW_MAJOR = 100
