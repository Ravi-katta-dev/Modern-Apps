package com.vayunmathur.youpipe.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.text.Cue
import androidx.media3.session.MediaController
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.FadeVisibility
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.platform.CastPlayback
import com.vayunmathur.youpipe.ui.AudioStream
import com.vayunmathur.youpipe.ui.SubtitleTrack
import com.vayunmathur.youpipe.ui.VideoChapter
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.ui.VideoStream
import com.vayunmathur.youpipe.util.YouPipeViewModel

/** The focusable option row: chapters / quality / language / captions / speed. */
@Composable
internal fun TvPlayerOptionsRow(
    onChapters: () -> Unit,
    onQuality: () -> Unit,
    onLanguage: () -> Unit,
    onCaptions: () -> Unit,
    onSpeed: () -> Unit,
    hasChapters: Boolean,
    hasLanguage: Boolean,
    hasCaptions: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (hasChapters) {
            TvButton(text = stringResource(R.string.chapter), onClick = onChapters)
        }
        TvButton(text = stringResource(R.string.label_quality), onClick = onQuality)
        if (hasLanguage) {
            TvButton(text = stringResource(R.string.language), onClick = onLanguage)
        }
        if (hasCaptions) {
            TvButton(text = stringResource(R.string.label_captions), onClick = onCaptions)
        }
        TvButton(text = stringResource(R.string.label_speed), onClick = onSpeed)
    }
}

/**
 * Subtitle cues, buffering spinner, transport overlay, option sheets and volume flash —
 * everything drawn over the player surface. Split from [TvPlayer] (FileLength): no
 * playback logic moves, only presentation.
 */
@Composable
internal fun BoxScope.TvPlayerOverlays(
    ypvm: YouPipeViewModel,
    videoInfo: VideoInfo,
    videoStreams: List<VideoStream>,
    audioStreams: List<AudioStream>,
    subtitles: List<SubtitleTrack>,
    segments: List<VideoChapter>,
    transport: CastPlayback.Transport,
    cues: List<Cue>,
    overlayVisible: Boolean,
    controller: MediaController?,
    languageEntries: List<Pair<String, String>>,
    language: String,
    onLanguage: (String) -> Unit,
    selectedSubtitle: SubtitleTrack?,
    onSubtitle: (SubtitleTrack?) -> Unit,
    currentVideoStream: VideoStream,
    onVideoStream: (VideoStream) -> Unit,
    showChapters: Boolean,
    onShowChapters: (Boolean) -> Unit,
    showQuality: Boolean,
    onShowQuality: (Boolean) -> Unit,
    showLanguage: Boolean,
    onShowLanguage: (Boolean) -> Unit,
    showCaptions: Boolean,
    onShowCaptions: (Boolean) -> Unit,
    showSpeed: Boolean,
    onShowSpeed: (Boolean) -> Unit,
) {
    if (cues.isNotEmpty()) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 120.dp, start = 48.dp, end = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            cues.forEach { cue ->
                cue.text?.let { text ->
                    Text(
                        text = text.toString(),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }

    if (transport.buffering) {
        CircularProgressIndicator(
            modifier = Modifier.align(Alignment.Center),
            color = Color.White,
        )
    }

    FadeVisibility(visible = overlayVisible) {
        Box(Modifier.fillMaxSize()) {
            TvTransportOverlay(
                title = videoInfo.name,
                author = videoInfo.author,
                hasNext = transport.hasNext,
                hasPrevious = transport.hasPrevious,
                speed = transport.speed,
                ypvm = ypvm,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            // Focusable option rows above the transport: chapters, quality, language, captions, speed.
            TvPlayerOptionsRow(
                onChapters = { onShowChapters(true) },
                onQuality = { onShowQuality(true) },
                onLanguage = { onShowLanguage(true) },
                onCaptions = { onShowCaptions(true) },
                onSpeed = { onShowSpeed(true) },
                hasChapters = segments.isNotEmpty(),
                hasLanguage = languageEntries.size > 1,
                hasCaptions = subtitles.isNotEmpty(),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp),
            )
        }
    }

    if (showChapters) {
        TvChapterSheet(
            segments = segments,
            onSeekTo = { controller?.seekTo(it) },
            onDismiss = { onShowChapters(false) },
        )
    }
    if (showQuality) {
        TvQualitySheet(
            streams = videoStreams,
            current = currentVideoStream,
            onSelect = { onVideoStream(it); onShowQuality(false) },
            onDismiss = { onShowQuality(false) },
        )
    }
    if (showLanguage) {
        TvLanguageSheet(
            entries = languageEntries,
            selected = language,
            onSelect = { onLanguage(it); onShowLanguage(false) },
            onDismiss = { onShowLanguage(false) },
        )
    }
    if (showCaptions) {
        TvCaptionSheet(
            tracks = subtitles,
            selected = selectedSubtitle,
            onSelect = { onSubtitle(it); onShowCaptions(false) },
            onDismiss = { onShowCaptions(false) },
        )
    }
    if (showSpeed) {
        TvSpeedSheet(
            current = transport.speed,
            onSelect = { speed ->
                CastPlayback.update { it.copy(speed = speed) }
                onShowSpeed(false)
            },
            onDismiss = { onShowSpeed(false) },
        )
    }

    val volumeFlash by TvTransportState.volumeFlash.collectAsState()
    volumeFlash?.let { level ->
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(32.dp)
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = "${(level * 100).toInt()}%",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
/**
 * The 300ms poll loop: transport position, sponsor-block skips, periodic history writes.
 *
 * Mirrors the phone `VideoPlayerPositionLoop` exactly (same interval, same skip-while-sponsor
 * rule, same 5s history cadence) — the only reader difference is that the transport here is
 * also what the TV overlay draws, so the loop is the single writer of `positionMs`.
 */
@Composable
internal fun TvPlayerPositionLoop(
    ypvm: YouPipeViewModel,
    videoInfo: VideoInfo,
    controller: MediaController?,
) {
    val sponsorSegments by ypvm.videoState.collectAsState()
    val sponsorBlockEnabled by ypvm.sponsorBlockEnabled.collectAsState()
    val categories by ypvm.sponsorBlockCategories.collectAsState()
    val segments = remember(sponsorSegments.sponsorSegments, categories) {
        sponsorSegments.sponsorSegments.filter { it.category in categories }
    }
    // Persist the final position on dispose — the phone's HistoryPersist equivalent.
    var lastPosition by remember(videoInfo.videoID) { mutableLongStateOf(0L) }
    var lastDuration by remember(videoInfo.videoID) { mutableLongStateOf(0L) }
    DisposableEffect(videoInfo.videoID) {
        onDispose {
            ypvm.upsertHistoryVideo(
                com.vayunmathur.youpipe.data.HistoryVideo.fromVideoData(
                    videoInfo.copy(duration = lastDuration),
                    lastPosition,
                ),
            )
        }
    }
    androidx.compose.runtime.LaunchedEffect(controller) {
        val player = controller ?: return@LaunchedEffect
        var lastHistoryUpsert = 0L
        while (true) {
            var position = player.currentPosition.coerceAtLeast(0L)
            if (sponsorBlockEnabled) {
                val current = segments.find { position in it.start until it.end }
                if (current != null) {
                    player.seekTo(current.end)
                    position = current.end
                }
            }
            // A TV scrub preview freezes the loop's write so the bar does not fight the D-pad,
            // the same guard the phone's `dragging` provides against its own seek bar.
            if (TvDpadInput.scrubPreviewMs == null) {
                CastPlayback.update {
                    it.copy(
                        positionMs = position,
                        bufferedMs = player.bufferedPosition.coerceAtLeast(0L),
                    )
                }
            }
            lastPosition = position
            lastDuration = player.duration.coerceAtLeast(0L)
            if (player.isPlaying) {
                val now = System.currentTimeMillis()
                if (now - lastHistoryUpsert >= TV_HISTORY_UPSERT_INTERVAL_MS) {
                    ypvm.upsertHistoryVideo(
                        com.vayunmathur.youpipe.data.HistoryVideo.fromVideoData(
                            videoInfo.copy(duration = lastDuration),
                            position,
                        ),
                    )
                    lastHistoryUpsert = now
                }
            }
            kotlinx.coroutines.delay(300)
        }
    }
}

private const val TV_HISTORY_UPSERT_INTERVAL_MS = 5_000L
