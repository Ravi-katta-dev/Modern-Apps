package com.vayunmathur.youpipe.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.library.ui.FadeVisibility
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.IconSkipNext
import com.vayunmathur.library.ui.IconSkipPrevious
import com.vayunmathur.youpipe.platform.CastPlayback
import com.vayunmathur.youpipe.util.SponsorSegment
import com.vayunmathur.youpipe.util.YouPipeViewModel

/**
 * Owns the one per-frame clock the TV player needs, and hands it down as a lambda.
 *
 * Copied from `cast/tv`'s `PlaybackClock` — not imported, because the receiver interpolates
 * between twice-a-second snapshots while this clock reads the local poll loop's transport
 * directly. Same shape for the same reason: passing the number down would put every reader in
 * a scope that invalidates sixty times a second; a lambda leaves the invalidation here.
 *
 * Keyed on transport identity so a fresh attach re-anchors, and the ticker stops while paused:
 * a frame callback per frame for a still bar would keep the panel awake for nothing.
 */
@Composable
internal fun TvPlaybackClock(
    content: @Composable (positionMs: () -> Long) -> Unit,
) {
    val transport by CastPlayback.transport.collectAsState()
    var frameNowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(transport.positionMs, transport.playing) {
        frameNowMs = System.currentTimeMillis()
        if (!transport.playing) return@LaunchedEffect
        while (true) {
            withFrameMillis { frameNowMs = System.currentTimeMillis() }
        }
    }
    // The scrub preview overrides the clock entirely while the user aims.
    content { TvDpadInput.scrubPreviewMs ?: transport.positionMs }
}

/**
 * The TV transport overlay: headline, seek bar, elapsed/total, and focusable controls.
 *
 * Two layers, like the receiver's overlay but focus-split:
 *
 * - The **readout** (headline, bar, times) is non-focusable: LEFT/RIGHT scrub and UP/DOWN
 *   volume are gestures handled by [TvDpadInput], not a focus ring between buttons.
 * - The **controls** (play/pause, next/previous) are focusable `Button`s below the readout,
 *   so CENTER/ENTER on a focused control acts even while the gesture path is scrubbing.
 *
 * Only mounted while [TvTransportState.visible]; auto-hide is owned there, not here.
 */
@Composable
fun TvTransportOverlay(
    title: String,
    author: String,
    hasNext: Boolean,
    hasPrevious: Boolean,
    speed: Float,
    ypvm: YouPipeViewModel,
    modifier: Modifier = Modifier,
) {
    FadeVisibility(visible = true, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                    ),
                )
                .padding(horizontal = 48.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TvPlaybackClock { positionMs ->
                val transport by CastPlayback.transport.collectAsState()
                val position = positionMs()
                // Sponsor markers: same filtered segments the phone seek row draws yellow,
                // read here so the transport already polling bufferedMs can also show them.
                val sponsorSegments = rememberTvSponsorSegments(ypvm)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (author.isNotBlank()) {
                                Text(
                                    text = author,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (speed != 1f) {
                            Text(
                                text = formatTvSpeed(speed),
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White.copy(alpha = 0.8f),
                            )
                        }
                    }
                    TvSeekBar(
                        positionMs = position,
                        bufferedMs = transport.bufferedMs,
                        durationMs = transport.durationMs,
                        sponsorSegments = sponsorSegments,
                        scrubbing = TvDpadInput.scrubPreviewMs != null,
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            text = formatTvElapsed(position),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White,
                        )
                        Text(
                            text = when {
                                transport.buffering -> "…"
                                transport.playing -> "❚❚"
                                else -> "▶"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White,
                        )
                        Text(
                            text = if (transport.durationMs > 0) formatTvElapsed(transport.durationMs) else "",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White,
                        )
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (hasPrevious) {
                    Button(onClick = { CastPlayback.onNavigate?.invoke(false) }) { IconSkipPrevious() }
                }
                TvPlayPauseButton()
                if (hasNext) {
                    Button(onClick = { CastPlayback.onNavigate?.invoke(true) }) { IconSkipNext() }
                }
            }
        }
    }
}

/** Play/pause as a focusable button — the gesture path also toggles, this is the visible twin. */
@Composable
private fun TvPlayPauseButton() {
    val transport by CastPlayback.transport.collectAsState()
    Button(
        onClick = {
            val player = CastPlayback.attachedPlayer ?: return@Button
            if (player.isPlaying) player.pause() else player.play()
        },
    ) {
        if (transport.playing) IconPause() else IconPlay()
    }
}

/**
 * Filtered sponsor segments for the TV seek bar: same `category in categories` filter the
 * phone `VideoPlayerSeekRow` draws as yellow markers. Reads the already-polled video state —
 * the transport loop already polls `bufferedMs`, no new data path.
 */
@Composable
private fun rememberTvSponsorSegments(ypvm: YouPipeViewModel): List<SponsorSegment> {
    val sponsorSegments by ypvm.videoState.collectAsState()
    val categories by ypvm.sponsorBlockCategories.collectAsState()
    return remember(sponsorSegments.sponsorSegments, categories) {
        sponsorSegments.sponsorSegments.filter { it.category in categories }
    }
}

/**
 * The seek bar, drawn as nested boxes rather than as a `Slider`.
 *
 * A `Slider` is a touch control: it wants a drag gesture and a focusable thumb, and on a
 * television it would offer both to a device that has neither. The remote handling lives in
 * [TvDpadInput]; this is a progress indicator that happens to be movable by the remote.
 *
 * Layers mirror the phone `VideoPlayerSeekRow`: buffered ghost, yellow sponsor markers,
 * played progress, thumb.
 */
@Composable
private fun TvSeekBar(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    sponsorSegments: List<SponsorSegment>,
    scrubbing: Boolean,
) {
    if (durationMs <= 0) return
    val progress = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    val buffered = (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (scrubbing) 10.dp else 6.dp)
            .clip(MaterialTheme.shapes.small)
            .background(Color.White.copy(alpha = 0.25f)),
        contentAlignment = Alignment.CenterStart,
    ) {
        // Buffered ghost under progress, like the phone's buffered slider.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(buffered)
                .background(Color.White.copy(alpha = 0.3f)),
        )
        // Yellow sponsor markers over the buffered layer, like the phone's Canvas rects.
        Canvas(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            sponsorSegments.forEach { segment ->
                val startX = (segment.start.toFloat() / durationMs) * size.width
                val endX = (segment.end.toFloat() / durationMs) * size.width
                drawRect(
                    color = Color.Yellow.copy(alpha = 0.7f),
                    topLeft = Offset(startX, 0f),
                    size = Size(endX - startX, size.height),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress)
                .background(MaterialTheme.colorScheme.primary),
        )
        Box(
            modifier = Modifier.fillMaxWidth(progress),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                modifier = Modifier
                    .size(if (scrubbing) 20.dp else 14.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** `H:MM:SS` past an hour, `M:SS` below it — what every other player on the panel does. */
private fun formatTvElapsed(positionMs: Long): String {
    val totalSeconds = (positionMs / 1_000).coerceAtLeast(0)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/** "2" rather than "2.0", "1.5" rather than "1.50". Matches the phone's speed readout. */
private fun formatTvSpeed(speed: Float): String {
    val r = Math.round(speed * 100f) / 100f
    return if (r % 1f == 0f) r.toInt().toString() else r.toString().trimEnd('0').trimEnd('.')
}
