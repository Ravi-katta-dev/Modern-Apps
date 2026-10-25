package com.vayunmathur.cast.tv.ui

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.cast.protocol.NowPlaying
import com.vayunmathur.cast.tv.R
import com.vayunmathur.cast.tv.platform.ArtworkFetcher
import com.vayunmathur.cast.tv.platform.PlaybackSnapshot
import com.vayunmathur.library.tv.TvBadge
import com.vayunmathur.library.tv.TvDimens
import com.vayunmathur.library.tv.TvIcon
import com.vayunmathur.library.tv.TvSeekRow
import com.vayunmathur.library.ui.IconMusicNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The cover, the track, and the lyrics, for a session with no picture.
 *
 * **Not part of `ReceiverContent`.** For an audio-only session `MirrorActivity` adds no
 * `SurfaceView` at all and fills its window with this beside the transport overlay, so
 * `ReceiverContent`'s mirroring arm is on screen for a few milliseconds and then never again.
 *
 * Artwork-led: a big card on the left, title/artist/source on the right with the seek row and
 * the play state, lyrics below the title block when the phone sent any. The backdrop is the
 * cover itself dimmed under scrims (or an accent gradient with no cover) - local pixels only,
 * never anything fetched off the network beyond the phone's own proxy.
 *
 * **Nothing on it is focusable.** There is no input model on this screen - the lyrics scroll
 * from the clock, so a scrollable focus target would be a thing the remote could move that
 * changes nothing and takes the D-pad away from seeking.
 *
 * [positionMs] is a lambda rather than a `Long` on purpose. It is read sixty times a second,
 * and passing the value would recompose the artwork and the entire lyrics column every frame
 * on hardware with no headroom to spare. Only the small progress subtree reads it, and the
 * lyrics index derives from it a few dozen times a song.
 */
@Composable
fun NowPlayingScreen(
    nowPlaying: NowPlaying,
    snapshot: PlaybackSnapshot,
    /** Who is casting: the app label, or the phone's name for a screen mirror. */
    sourceName: String,
    /** Where playback is right now, read per frame. See the note above about why it is a lambda. */
    positionMs: () -> Long,
    artwork: ArtworkFetcher?,
    modifier: Modifier = Modifier,
) {
    val cover = rememberArtwork(nowPlaying.artworkResourceId, artwork)
    val hasLyrics = nowPlaying.lyrics.isNotEmpty() || nowPlaying.plainLyrics.isNotEmpty()

    Box(modifier = modifier.fillMaxSize()) {
        NowPlayingBackdrop(cover)
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = TvDimens.SafeHorizontal,
                    end = TvDimens.SafeHorizontal,
                    top = TvDimens.SafeVertical,
                    bottom = 16.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // **Sized from the height it is given, never from a constant.** Squaring a filled
            // height cannot overflow whatever the window turns out to be.
            ArtworkCard(cover, Modifier.fillMaxHeight())
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // **The title replaces the app's name rather than sitting beside it.** A user
                // looking at a track already knows which app they started; the source survives
                // as the eyebrow above it.
                TitleBlock(
                    title = nowPlaying.title,
                    author = nowPlaying.author,
                    album = nowPlaying.album,
                    sourceName = sourceName,
                    snapshot = snapshot,
                )
                NowPlayingProgress(positionMs = positionMs, snapshot = snapshot)
                if (hasLyrics) {
                    Lyrics(
                        nowPlaying = nowPlaying,
                        positionMs = positionMs,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * The cover dimmed under scrims, or an accent gradient with no cover.
 *
 * A mask-free scrim rather than a second background owner: `MirrorActivity` paints the window
 * once behind both this and the transport bar, so the two stay one continuous surface.
 */
@Composable
private fun NowPlayingBackdrop(cover: ImageBitmap?) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize()) {
        if (cover != null) {
            Image(
                bitmap = cover,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = BACKDROP_ALPHA,
            )
        } else {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to scheme.primaryContainer.copy(alpha = 0.35f),
                        1f to Color.Transparent,
                    ),
                ),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to scheme.background.copy(alpha = 0.45f),
                    0.5f to scheme.background.copy(alpha = 0.72f),
                    1f to scheme.background,
                ),
            ),
        )
    }
}

/**
 * The cover, or a drawn stand-in for one.
 *
 * **Never a broken image.** No artwork is by far the most likely outcome for a library of loose
 * files, and it is also what a failed fetch and a fetch still in flight look like - so all three
 * land on a tonal surface that says so in words rather than on anything that reads as an error.
 */
@Composable
private fun ArtworkCard(cover: ImageBitmap?, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.large
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(scheme.surfaceVariant)
            .border(2.dp, scheme.primary.copy(alpha = 0.5f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) {
            Image(
                bitmap = cover,
                contentDescription = stringResource(R.string.tv_now_playing_artwork),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    TvIcon { IconMusicNote() }
                }
                Text(
                    text = stringResource(R.string.tv_now_playing_no_artwork),
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TitleBlock(
    title: String,
    author: String,
    album: String,
    sourceName: String,
    snapshot: PlaybackSnapshot,
) {
    val scheme = MaterialTheme.colorScheme
    val state = snapshot.state
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (sourceName.isNotBlank()) {
            Text(
                text = sourceName.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = title.ifBlank { sourceName },
            style = MaterialTheme.typography.displaySmall,
            color = scheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (author.isNotBlank()) {
            Text(
                text = author,
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onSurface.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (album.isNotBlank()) {
            Text(
                text = album,
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The state, not a button: a stall and a pause look identical on a still screen, and
        // only one of them is the user's own doing.
        when {
            state.buffering -> TvBadge(text = stringResource(R.string.tv_now_playing_buffering))
            !state.playing -> TvBadge(text = stringResource(R.string.tv_now_playing_paused))
        }
    }
}

/**
 * The seek row. Reads the per-frame clock in this small subtree only, so the artwork and the
 * lyrics column above it are not in a scope that invalidates sixty times a second.
 */
@Composable
private fun NowPlayingProgress(positionMs: () -> Long, snapshot: PlaybackSnapshot) {
    val position = positionMs()
    val duration = snapshot.state.durationMs
    if (duration <= 0) return
    TvSeekRow(
        elapsed = DateUtils.formatElapsedTime((position / 1_000).coerceAtLeast(0)),
        total = DateUtils.formatElapsedTime((duration / 1_000).coerceAtLeast(0)),
        progress = (position.toFloat() / duration).coerceIn(0f, 1f),
    )
}

/**
 * The decoded cover for [resourceId], or null while it is being fetched and if it never arrives.
 *
 * Keyed on the resource id, so a track change starts a fresh fetch and - more importantly - drops
 * the previous cover the instant the id changes rather than leaving it on screen over the next
 * track.
 */
@Composable
private fun rememberArtwork(resourceId: String, artwork: ArtworkFetcher?): ImageBitmap? {
    if (resourceId.isEmpty() || artwork == null) return null
    val bitmap by produceState<ImageBitmap?>(initialValue = null, resourceId, artwork) {
        value = withContext(Dispatchers.IO) { artwork.fetch(resourceId)?.asImageBitmap() }
    }
    return bitmap
}

private const val BACKDROP_ALPHA = 0.22f
