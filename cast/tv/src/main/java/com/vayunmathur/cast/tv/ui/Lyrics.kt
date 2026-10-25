package com.vayunmathur.cast.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.cast.protocol.LyricLine
import com.vayunmathur.cast.protocol.NowPlaying

/**
 * Timed lyrics that follow the clock, or untimed ones as a block.
 *
 * `userScrollEnabled = false` and no focus: this list is driven entirely from [positionMs].
 *
 * The index comes from a `derivedStateOf` over that lambda, which is what keeps the cost of a
 * per-frame clock off this column - it recomputes every frame but only *invalidates* when the
 * answer changes, which is a few dozen times a song rather than sixty times a second.
 *
 * **The current line is scrolled to the top, and what is below it fades out.** Upcoming lyrics
 * read downward, so the gradient falls in the direction the eye is already travelling and the
 * line being sung is the most legible thing on the panel. It is a mask over the whole column
 * rather than a per-line alpha, because a line half in and half out of the fade should fade by
 * the same amount as the pixels around it.
 */
@Composable
fun Lyrics(nowPlaying: NowPlaying, positionMs: () -> Long, modifier: Modifier = Modifier) {
    if (nowPlaying.lyrics.isEmpty()) {
        // Nothing to highlight and nothing to scroll to, so it is a block the user reads at
        // their own pace. Scrollable would be a lie: there is no input for it.
        LazyColumn(modifier = modifier.fadingEdge(), userScrollEnabled = false) {
            item {
                Text(
                    text = nowPlaying.plainLyrics,
                    fontSize = LYRIC_SIZE_SP.sp,
                    lineHeight = LYRIC_LINE_HEIGHT_EM.em,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
            }
        }
        return
    }

    val lines = nowPlaying.lyrics
    // `rememberUpdatedState`, and it is load-bearing. `PlaybackClock` builds a fresh lambda per
    // recomposition that closes over *that* composition's snapshot; a `remember` keyed only on
    // the lines would pin the first one for the whole track, so the highlight would keep
    // extrapolating from the first anchor and never see a pause, a seek or a scrub. Only
    // `frameNowMs` is live inside that lambda - everything else in it is captured by value.
    val clock by rememberUpdatedState(positionMs)
    val current by remember(lines) {
        derivedStateOf { currentLyricIndex(lines, clock()) }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(current, lines) {
        if (current >= 0) runCatching { listState.animateScrollToItem(current) }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fadingEdge(),
        userScrollEnabled = false,
        // **Between items, and [LYRIC_LINE_HEIGHT_EM] within one.** A long lyric wraps, and
        // spacing alone leaves the wrapped halves of one line closer together than the default
        // leading allows - so two lines of one lyric collide while the gap to the next lyric
        // looks generous.
        verticalArrangement = Arrangement.spacedBy(LYRIC_GAP_DP.dp),
        // Room to scroll the last lyric up to where the current one is read, instead of it
        // stopping part-way and leaving the highlight pinned at the bottom for the outro.
        contentPadding = PaddingValues(bottom = 240.dp),
    ) {
        itemsIndexed(lines) { index, line ->
            val isCurrent = index == current
            Text(
                text = line.text,
                fontSize = if (isCurrent) LYRIC_CURRENT_SIZE_SP.sp else LYRIC_SIZE_SP.sp,
                lineHeight = LYRIC_LINE_HEIGHT_EM.em,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                },
            )
        }
    }
}

/**
 * Fades the bottom of a column out to nothing.
 *
 * `DstIn` over an offscreen layer, which is the one way to make a *mask* rather than a scrim:
 * a gradient drawn in the background colour would be opaque over whatever sits beside the
 * column, and one drawn in black would be a grey wash. Offscreen compositing is required for
 * the blend to have the column's own alpha to work against.
 */
private fun Modifier.fadingEdge(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                FADE_START to Color.White,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

/**
 * Which line is current at [positionMs], or -1 before the first one.
 *
 * The same rule the phone applies, deliberately written out rather than shared: `:music` and
 * this module have no common dependency, and one `indexOfLast` is a smaller thing to duplicate
 * than a module boundary is to move. The boundary is inclusive - a line whose timestamp is
 * exactly the position is the line being sung.
 */
private fun currentLyricIndex(lines: List<LyricLine>, positionMs: Long): Int =
    lines.indexOfLast { it.atMs <= positionMs }

/**
 * Lyric sizes, for a panel that reports 960x540dp.
 *
 * Legible across a room and small enough that half a dozen lines fit beside the cover, which
 * is what makes the highlight look like it is moving through a song rather than replacing the
 * screen.
 */
private const val LYRIC_CURRENT_SIZE_SP = 28
private const val LYRIC_SIZE_SP = 22
private const val LYRIC_GAP_DP = 20

/** Leading within one wrapped lyric, as a multiple of its own size. */
private const val LYRIC_LINE_HEIGHT_EM = 1.35f

/** Where the fade begins, as a fraction of the column's height. */
private const val FADE_START = 0.55f
