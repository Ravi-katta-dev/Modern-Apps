package com.vayunmathur.cast.tv.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.cast.protocol.NowPlaying
import com.vayunmathur.cast.protocol.PlaybackAction
import com.vayunmathur.cast.protocol.PlaybackCommand
import com.vayunmathur.cast.tv.R
import com.vayunmathur.cast.tv.platform.PlaybackSnapshot
import com.vayunmathur.cast.tv.platform.ReceiverController
import com.vayunmathur.library.tv.TvDimens
import com.vayunmathur.library.tv.TvIconButton
import com.vayunmathur.library.tv.TvSeekRow
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.IconSkipNext
import com.vayunmathur.library.ui.IconSkipPrevious

/**
 * The transport controls: a title block and seek row over a bottom gradient scrim, with icon
 * buttons for the actions the remote can take.
 *
 * **Transparent where it is not painted.** The scrim is the only background, a vertical gradient
 * that fades the picture through the controls; over audio (`scrim = false`) there is no paint
 * at all, because the caller already painted the window and the bar belongs to the same sheet
 * of colour as the cover and the lyrics above it.
 *
 * Key and focus semantics are unchanged from the old overlay: the remote's dedicated keys and
 * its D-pad as *gestures* are the whole input path (`MirrorActivity.dispatchKeyEvent`), and the
 * icon buttons here mirror those same commands for a remote whose `Media*` keys never arrive.
 * They are focusable tv-material targets, and focusing one must not move the seek preview - it
 * only shows where playback is.
 */
@Composable
fun TransportOverlay(
    snapshot: PlaybackSnapshot,
    /** Who is casting, shown until the phone says what is playing. Usually the app's name. */
    sourceName: String,
    /** What is playing, or null when the phone has not said - an old build, or the first moment. */
    nowPlaying: NowPlaying?,
    /** Where playback is right now, including any scrub preview. Read per frame. */
    positionMs: () -> Long,
    /** Whether a scrub is in progress, which thickens the bar. */
    scrubbing: Boolean,
    modifier: Modifier = Modifier,
    /**
     * Whether to darken behind itself.
     *
     * True over a picture, where the gradient is what keeps the frame visible through the
     * controls and the numbers legible against whatever happens to be behind them. **False for
     * audio**, where there is no picture to see through and the scrim would be a black band
     * across the bottom of an otherwise continuous surface.
     */
    scrim: Boolean = true,
) {
    val state = snapshot.state
    val position = positionMs()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (scrim) {
                    Modifier.background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                        ),
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = TvDimens.SafeHorizontal, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TitleBlock(
            nowPlaying = nowPlaying,
            sourceName = sourceName,
            speed = state.speed,
        )

        if (state.durationMs > 0) {
            TvSeekRow(
                elapsed = DateUtils.formatElapsedTime((position / 1_000).coerceAtLeast(0)),
                total = DateUtils.formatElapsedTime((state.durationMs / 1_000).coerceAtLeast(0)),
                progress = (position.toFloat() / state.durationMs).coerceIn(0f, 1f),
                active = scrubbing,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // **The action the centre key will take.** Read as a status it would be the other
            // way round - which is exactly why it has to match the button it sits next to.
            // Buffering is its own case: a stall and a pause look identical on a frozen
            // picture, and only one of them is the user's own doing.
            TvIconButton(
                onClick = { ReceiverController.send(PlaybackCommand(PlaybackAction.Previous)) },
                icon = { IconSkipPrevious() },
                enabled = state.hasPrevious,
            )
            // Always enabled, like the centre key it mirrors: the gesture path sends Toggle
            // unconditionally, and the button must never disagree with it.
            TvIconButton(
                onClick = { ReceiverController.send(PlaybackCommand(PlaybackAction.Toggle)) },
                icon = { if (state.playing) IconPause() else IconPlay() },
                size = 64.dp,
            )
            TvIconButton(
                onClick = { ReceiverController.send(PlaybackCommand(PlaybackAction.Next)) },
                icon = { IconSkipNext() },
                enabled = state.hasNext,
            )
        }

        Text(
            text = stringResource(R.string.tv_transport_hint),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.6f),
        )
    }
}

/**
 * What is playing, or who is casting when that is all the television knows.
 *
 * **The title replaces the app's name rather than sitting beside it.** "Receiving from YouPipe"
 * was the most the receiver could say when nothing crossed the protocol but pixels; with a
 * title in hand it is the less useful of the two, and a user looking at a video already knows
 * which app they started. The fallback keeps it for an old phone, an app that sends no
 * metadata, and the moment before the first snapshot lands.
 *
 * **Every field it has, and no field it does not**, which is why there is no video-shaped
 * branch here: a track fills all three lines and a video fills two, because `album` is what a
 * video has nothing to put in. Stacked rather than joined onto one line - a video title is long
 * and a channel name is short, and one line holding both would truncate the title to fit
 * something that is not the title.
 */
@Composable
private fun TitleBlock(nowPlaying: NowPlaying?, sourceName: String, speed: Float) {
    Column {
        Text(
            text = nowPlaying?.title.orEmpty().ifBlank { sourceName },
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TransportSubtitle(nowPlaying?.author.orEmpty(), alpha = 0.7f)
        TransportSubtitle(nowPlaying?.album.orEmpty(), alpha = 0.5f)
        if (speed != 1f) {
            Text(
                text = stringResource(R.string.tv_transport_speed, formatSpeed(speed)),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}

/** One line beneath the title, or nothing at all when there is nothing to put on it. */
@Composable
private fun TransportSubtitle(text: String, alpha: Float) {
    if (text.isBlank()) return
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = Color.White.copy(alpha = alpha),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** "2" rather than "2.0", "1.5" rather than "1.50". Matches YouPipe's own speed readout. */
private fun formatSpeed(speed: Float): String {
    val rounded = Math.round(speed * 100f) / 100f
    return if (rounded % 1f == 0f) {
        rounded.toInt().toString()
    } else {
        rounded.toString().trimEnd('0').trimEnd('.')
    }
}
