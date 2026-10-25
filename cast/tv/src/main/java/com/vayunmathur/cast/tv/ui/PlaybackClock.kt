package com.vayunmathur.cast.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import com.vayunmathur.cast.tv.platform.PlaybackSnapshot

/**
 * Owns the one per-frame clock a served session needs, and hands it down as a lambda.
 *
 * The now-playing screen's lyrics need the same interpolated position and the same
 * scrub-preview override as the transport bar, and two tickers would be two answers to one
 * question.
 *
 * **Hoisted as a lambda, not as a value, and the distinction is the whole point.** Passing the
 * number down would put every reader of it in the recomposition scope that invalidates sixty
 * times a second - which on TV-class hardware means redrawing a bitmap and a full lyrics column
 * per frame. Passing a lambda leaves the invalidation here, where the state is read, and lets
 * each reader decide how much of itself depends on it.
 *
 * Keyed on the snapshot so every fresh one re-anchors, and the ticker stops while playback is
 * paused: a frame callback per frame for a still bar would keep the panel awake for nothing.
 */
@Composable
fun PlaybackClock(
    snapshot: PlaybackSnapshot,
    /** A scrub the user is composing, which overrides the clock entirely. Null when not scrubbing. */
    scrubPreviewMs: Long?,
    content: @Composable (positionMs: () -> Long) -> Unit,
) {
    var frameNowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(snapshot) {
        frameNowMs = System.currentTimeMillis()
        if (!snapshot.state.playing) return@LaunchedEffect
        while (true) {
            withFrameMillis { frameNowMs = System.currentTimeMillis() }
        }
    }
    content { scrubPreviewMs ?: snapshot.positionAt(frameNowMs) }
}
