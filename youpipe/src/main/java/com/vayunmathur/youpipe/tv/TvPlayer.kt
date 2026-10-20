package com.vayunmathur.youpipe.tv

import android.content.ComponentName
import android.text.format.DateUtils
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.google.common.util.concurrent.MoreExecutors
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.FadeVisibility
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.youpipe.platform.CastPlayback
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.ui.AudioStream
import com.vayunmathur.youpipe.ui.selectDefaultVideoStream
import com.vayunmathur.youpipe.ui.SubtitleTrack
import com.vayunmathur.youpipe.ui.VideoChapter
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.ui.VideoStream
import com.vayunmathur.youpipe.util.PlaybackService
import com.vayunmathur.youpipe.util.YouPipeViewModel
import kotlin.math.min

/**
 * The TV player: the same media3 `PlayerSurface` + `MediaController`/`PlaybackService` core as
 * the phone, with the touch-only gesture layer replaced.
 *
 * Gone from the phone player: `detectTapGestures` (tap toggle, double-tap ±10s, long-press 2x),
 * the 2s auto-hide timer, hover affordances, and orientation-lock fullscreen (the activity is
 * landscape-locked in the manifest). In their place: [TvTransportOverlay] (non-focusable
 * readout + focusable controls), the [TvDpadInput] key map (scrub commit on UP, volume nudge,
 * BACK dismisses overlay first), and [TvPlaybackClock] (one per-frame ticker).
 *
 * PiP is left alone — the manifest flag is harmless on TV and the phone owns the behavior.
 */
@OptIn(UnstableApi::class)
@Composable
fun TvPlayer(
    ypvm: YouPipeViewModel,
    videoInfo: VideoInfo,
    videoStreams: List<VideoStream>,
    audioStreams: List<AudioStream>,
    subtitles: List<SubtitleTrack>,
    segments: List<VideoChapter>,
) {
    if (videoStreams.isEmpty()) return
    val context = LocalContext.current
    val messenger = rememberMessenger()

    var controller by remember { mutableStateOf<MediaController?>(null) }
    var cues by remember { mutableStateOf<List<Cue>>(emptyList()) }
    var showChapters by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var showCaptions by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    val overlayVisible by TvTransportState.visible.collectAsState()

    val deviceHeightPx = remember(context) {
        context.resources.displayMetrics.let { min(it.widthPixels, it.heightPixels) }
    }
    var currentVideoStream by remember(videoStreams) {
        mutableStateOf(selectDefaultVideoStream(videoStreams, deviceHeightPx) ?: videoStreams.first())
    }
    // TV keeps the phone's audio rule: highest-quality opus for the selected language.
    val language = remember(audioStreams) {
        val codes = audioStreams.map { it.language }.distinct()
        if ("en" in codes) "en" else codes.firstOrNull().orEmpty()
    }
    val currentAudioStream = remember(audioStreams, language) {
        audioStreams.filter { it.language == language }.maxByOrNull { it.bitrate }
    }
    var selectedSubtitle by remember { mutableStateOf<SubtitleTrack?>(null) }
    val transport by CastPlayback.transport.collectAsState()
    val playbackErrorMessage = stringResource(R.string.playback_error)

    // Controller lifecycle: same SessionToken → PlaybackService connect as the phone.
    DisposableEffect(Unit) {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        var disposed = false
        future.addListener({
            val c = try {
                future.get()
            } catch (_: java.util.concurrent.CancellationException) {
                null
            } catch (_: Exception) {
                AppMessages.show(playbackErrorMessage)
                null
            }
            if (c == null) return@addListener
            if (disposed) {
                c.stop()
                c.release()
            } else {
                controller = c
            }
        }, MoreExecutors.directExecutor())
        onDispose {
            disposed = true
            controller?.let { it.stop(); it.release() }
            MediaController.releaseFuture(future)
        }
    }

    // Media item: same extras contract (audio URI + itag) the SABR source resolves in-process.
    LaunchedEffect(controller, currentVideoStream, currentAudioStream, videoInfo.name, subtitles) {
        val player = controller ?: return@LaunchedEffect
        val metadata = MediaMetadata.Builder()
            .setTitle(videoInfo.name)
            .setArtist(videoInfo.author)
            .build()
        val item = MediaItem.Builder()
            .setUri(currentVideoStream.url)
            .setMediaMetadata(metadata)
            .setSubtitleConfigurations(
                subtitles.map { sub ->
                    MediaItem.SubtitleConfiguration.Builder(sub.url.toUri())
                        .setMimeType(sub.mimeType)
                        .setLanguage(sub.languageTag)
                        .build()
                },
            )
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
    }

    // Transport observer: aspect, cues, playing/buffering into the shared transport.
    DisposableEffect(controller) {
        val player = controller ?: return@DisposableEffect onDispose {}
        CastPlayback.update { it.copy(playing = player.isPlaying) }
        CastPlayback.attachPlayer(player)
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                    events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)
                ) {
                    CastPlayback.update {
                        it.copy(
                            durationMs = player.duration.coerceAtLeast(0L),
                            buffering = player.playbackState == Player.STATE_BUFFERING,
                            playing = player.isPlaying,
                        )
                    }
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                CastPlayback.update { it.copy(playing = isPlaying) }
            }
            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                cues = cueGroup.cues
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            CastPlayback.detachPlayer(player)
        }
    }

    // Position loop + history: same 300ms poll the phone runs (sponsor-block included).
    TvPlayerPositionLoop(ypvm, videoInfo, controller)

    // Subtitle + tempo application.
    LaunchedEffect(controller, selectedSubtitle) {
        val player = controller ?: return@LaunchedEffect
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            if (selectedSubtitle == null) {
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            } else {
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                setPreferredTextLanguage(selectedSubtitle!!.languageTag)
            }
        }.build()
    }
    LaunchedEffect(controller, transport.speed) {
        val pitch = transport.speed
        controller?.playbackParameters = PlaybackParameters(transport.speed, pitch)
    }

    // BACK dismisses the topmost overlay first: chapter sheet, then menus, then transport.
    DisposableEffect(showChapters, showQuality, showCaptions, showSpeed, overlayVisible) {
        TvDpadInput.overlayDismiss = {
            when {
                showChapters -> { showChapters = false; true }
                showQuality -> { showQuality = false; true }
                showCaptions -> { showCaptions = false; true }
                showSpeed -> { showSpeed = false; true }
                overlayVisible -> { TvTransportState.hide(); true }
                else -> false
            }
        }
        onDispose {
            if (TvDpadInput.overlayDismiss != null) TvDpadInput.overlayDismiss = null
        }
    }
    DisposableEffect(Unit) {
        TvTransportState.reveal()
        onDispose { TvTransportState.hide() }
    }

    val focusRequester = remember { FocusRequester() }
    TvInitialFocus(focusRequester)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        controller?.let { player ->
            PlayerSurface(
                player = player,
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester)
                    .onFocusChanged { TvDpadInput.playerFocused = it.isFocused },
                surfaceType = SURFACE_TYPE_SURFACE_VIEW,
            )
        } ?: Box(Modifier.fillMaxSize().background(Color.Black))

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
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
                // Focusable option rows above the transport: chapters, quality, captions, speed.
                TvPlayerOptionsRow(
                    onChapters = { showChapters = true },
                    onQuality = { showQuality = true },
                    onCaptions = { showCaptions = true },
                    onSpeed = { showSpeed = true },
                    hasChapters = segments.isNotEmpty(),
                    hasCaptions = subtitles.isNotEmpty(),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp),
                )
            }
        }

        if (showChapters) {
            TvChapterSheet(
                segments = segments,
                onSeekTo = { controller?.seekTo(it) },
                onDismiss = { showChapters = false },
            )
        }
        if (showQuality) {
            TvQualitySheet(
                streams = videoStreams,
                current = currentVideoStream,
                onSelect = { currentVideoStream = it; showQuality = false },
                onDismiss = { showQuality = false },
            )
        }
        if (showCaptions) {
            TvCaptionSheet(
                tracks = subtitles,
                selected = selectedSubtitle,
                onSelect = { selectedSubtitle = it; showCaptions = false },
                onDismiss = { showCaptions = false },
            )
        }
        if (showSpeed) {
            TvSpeedSheet(
                current = transport.speed,
                onSelect = { speed ->
                    CastPlayback.update { it.copy(speed = speed) }
                    showSpeed = false
                },
                onDismiss = { showSpeed = false },
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
}

/** The focusable option row: chapters / quality / captions / speed. */
@Composable
private fun TvPlayerOptionsRow(
    onChapters: () -> Unit,
    onQuality: () -> Unit,
    onCaptions: () -> Unit,
    onSpeed: () -> Unit,
    hasChapters: Boolean,
    hasCaptions: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (hasChapters) {
            androidx.tv.material3.Button(onClick = onChapters) {
                Text(stringResource(R.string.chapter))
            }
        }
        androidx.tv.material3.Button(onClick = onQuality) {
            Text(stringResource(R.string.label_quality))
        }
        if (hasCaptions) {
            androidx.tv.material3.Button(onClick = onCaptions) {
                Text(stringResource(R.string.label_captions))
            }
        }
        androidx.tv.material3.Button(onClick = onSpeed) {
            Text(stringResource(R.string.label_speed))
        }
    }
}

/** D-pad chapter list: focus a chapter, CENTER/ENTER seeks. Replaces the phone bottom sheet. */
@Composable
internal fun TvChapterSheet(
    segments: List<VideoChapter>,
    onSeekTo: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val transport by CastPlayback.transport.collectAsState()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    TvInitialFocus(focusRequester)
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f)),
    ) {
        Column(Modifier.fillMaxSize().padding(48.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = stringResource(R.string.chapter), style = MaterialTheme.typography.headlineSmall)
                androidx.tv.material3.Button(onClick = onDismiss, modifier = Modifier.focusRequester(focusRequester)) {
                    Text(stringResource(R.string.action_close))
                }
            }
            LazyColumn(state = listState, modifier = Modifier.padding(top = 16.dp)) {
                items(segments, key = { it.time }) { chapter ->
                    val index = segments.indexOf(chapter)
                    val isCurrent = transport.positionMs >= chapter.time &&
                        (segments.getOrNull(index + 1)?.time?.let { transport.positionMs < it } ?: true)
                    Surface(
                        onClick = { onSeekTo(chapter.time.toLong()); onDismiss() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!chapter.previewURL.isNullOrEmpty()) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(chapter.previewURL)
                                        .memoryCacheKey("chapter-${chapter.time}")
                                        .build(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxWidth(0.2f),
                                )
                            }
                            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                Text(
                                    text = chapter.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = DateUtils.formatElapsedTime(chapter.time.toLong() / 1000),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (isCurrent) IconCheck()
                        }
                    }
                }
            }
        }
    }
}

/** D-pad quality list: one focusable row per stream. Replaces the phone dropdown. */
@Composable
internal fun TvQualitySheet(
    streams: List<VideoStream>,
    current: VideoStream,
    onSelect: (VideoStream) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvOptionSheet(title = stringResource(R.string.label_quality), onDismiss = onDismiss, modifier = modifier) {
        items(streams, key = { it.quality + it.height }) { stream ->
            Surface(onClick = { onSelect(stream) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(
                    Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = stream.quality, style = MaterialTheme.typography.titleMedium)
                    if (stream == current) IconCheck()
                }
            }
        }
    }
}

/** D-pad caption list: off + one row per track. Replaces the phone dropdown. */
@Composable
internal fun TvCaptionSheet(
    tracks: List<SubtitleTrack>,
    selected: SubtitleTrack?,
    onSelect: (SubtitleTrack?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvOptionSheet(title = stringResource(R.string.label_captions), onDismiss = onDismiss, modifier = modifier) {
        item {
            Surface(onClick = { onSelect(null) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(
                    Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = stringResource(R.string.label_captions_off), style = MaterialTheme.typography.titleMedium)
                    if (selected == null) IconCheck()
                }
            }
        }
        items(tracks, key = { it.languageTag + it.url }) { track ->
            Surface(onClick = { onSelect(track) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(
                    Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = track.displayName, style = MaterialTheme.typography.titleMedium)
                    if (track == selected) IconCheck()
                }
            }
        }
    }
}

/** D-pad speed list: the same range the phone offers, nothing the phone cannot do. */
@Composable
internal fun TvSpeedSheet(
    current: Float,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvOptionSheet(title = stringResource(R.string.label_speed), onDismiss = onDismiss, modifier = modifier) {
        items(TV_SPEEDS, key = { it }) { speed ->
            Surface(onClick = { onSelect(speed) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(
                    Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = formatTvSpeedLabel(speed), style = MaterialTheme.typography.titleMedium)
                    if (speed == current) IconCheck()
                }
            }
        }
    }
}

/** Shared sheet frame: title + close, focus starts on close, BACK dismisses. */
@Composable
private fun TvOptionSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    TvInitialFocus(focusRequester)
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f)),
    ) {
        Column(Modifier.fillMaxSize().padding(48.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = title, style = MaterialTheme.typography.headlineSmall)
                androidx.tv.material3.Button(onClick = onDismiss, modifier = Modifier.focusRequester(focusRequester)) {
                    Text(stringResource(R.string.action_close))
                }
            }
            LazyColumn(Modifier.padding(top = 16.dp), content = content)
        }
    }
}

private val TV_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

private fun formatTvSpeedLabel(speed: Float): String =
    if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"

/**
 * The 300ms poll loop: transport position, sponsor-block skips, periodic history writes.
 *
 * Mirrors the phone `VideoPlayerPositionLoop` exactly (same interval, same skip-while-sponsor
 * rule, same 5s history cadence) — the only reader difference is that the transport here is
 * also what the TV overlay draws, so the loop is the single writer of `positionMs`.
 */
@Composable
private fun TvPlayerPositionLoop(
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
