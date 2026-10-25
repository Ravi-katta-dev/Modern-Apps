package com.vayunmathur.youpipe.tv

import android.content.ComponentName
import android.text.format.DateUtils
import android.view.WindowManager
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
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.FadeVisibility
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.youpipe.findActivity
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

    // Keep the screen on while the player is composed — same DisposableEffect pattern
    // as the phone's VideoPlayerViewState.
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    var controller by remember { mutableStateOf<MediaController?>(null) }
    var cues by remember { mutableStateOf<List<Cue>>(emptyList()) }
    var showChapters by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var showLanguage by remember { mutableStateOf(false) }
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
    // Use displayName for language UI when available, fallback to language code (same as phone).
    val languageEntries = remember(audioStreams) {
        audioStreams.map { it.language to (it.displayName ?: it.language) }
            .distinctBy { it.first }
            .sortedBy { it.first }
    }
    val audioLanguages = languageEntries.map { it.first }
    var language by remember(audioStreams) {
        mutableStateOf(
            if ("en" in audioLanguages) "en" else audioLanguages.firstOrNull().orEmpty(),
        )
    }
    // Keep language valid when audioStreams change (new video).
    LaunchedEffect(audioLanguages) {
        if (language !in audioLanguages) {
            language = if ("en" in audioLanguages) "en" else audioLanguages.firstOrNull().orEmpty()
        }
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
    DisposableEffect(showChapters, showQuality, showLanguage, showCaptions, showSpeed, overlayVisible) {
        TvDpadInput.overlayDismiss = {
            when {
                showChapters -> { showChapters = false; true }
                showQuality -> { showQuality = false; true }
                showLanguage -> { showLanguage = false; true }
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

        TvPlayerOverlays(
            ypvm = ypvm,
            videoInfo = videoInfo,
            videoStreams = videoStreams,
            audioStreams = audioStreams,
            subtitles = subtitles,
            segments = segments,
            transport = transport,
            cues = cues,
            overlayVisible = overlayVisible,
            controller = controller,
            languageEntries = languageEntries,
            language = language,
            onLanguage = { language = it },
            selectedSubtitle = selectedSubtitle,
            onSubtitle = { selectedSubtitle = it },
            currentVideoStream = currentVideoStream,
            onVideoStream = { currentVideoStream = it },
            showChapters = showChapters,
            onShowChapters = { showChapters = it },
            showQuality = showQuality,
            onShowQuality = { showQuality = it },
            showLanguage = showLanguage,
            onShowLanguage = { showLanguage = it },
            showCaptions = showCaptions,
            onShowCaptions = { showCaptions = it },
            showSpeed = showSpeed,
            onShowSpeed = { showSpeed = it },
        )
    }
}

