package com.vayunmathur.uniflix.ui.tv

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.tv.TvAspect
import com.vayunmathur.library.tv.TvMediaCard
import com.vayunmathur.uniflix.R
import com.vayunmathur.uniflix.data.ContinueWatchingEntry
import com.vayunmathur.uniflix.data.TmdbImageUrl
import com.vayunmathur.uniflix.data.WatchlistEntry
import com.vayunmathur.uniflix.domain.Episode
import com.vayunmathur.uniflix.domain.MediaItem
import com.vayunmathur.uniflix.domain.MediaType

/** A resume entry as catalog item, for hero text and artwork fallbacks. */
internal fun ContinueWatchingEntry.toMediaItem(): MediaItem = MediaItem(
    tmdbId = tmdbId,
    mediaType = if (mediaType == "tv") MediaType.TV else MediaType.MOVIE,
    title = title,
    posterPath = posterPath,
)

/** A watchlist row as catalog item, for shared card rendering. */
internal fun WatchlistEntry.toMediaItem(): MediaItem = MediaItem(
    tmdbId = tmdbId,
    mediaType = if (mediaType == "tv") MediaType.TV else MediaType.MOVIE,
    title = title,
    posterPath = posterPath,
)

/** Best artwork for a hero backdrop: the backdrop, falling back to the poster. */
internal fun heroArtwork(item: MediaItem): String? =
    TmdbImageUrl.backdrop(item.backdropPath) ?: TmdbImageUrl.poster(item.posterPath)

/** Stable key for backdrop crossfades and lazy-list items. */
internal fun mediaKey(item: MediaItem): String = "${item.mediaType}:${item.tmdbId}"

/** 0..1 resume fraction, or null when there is nothing meaningful to draw. */
internal fun resumeProgress(positionMs: Long, durationMs: Long): Float? =
    if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else null

/** "Movie"/"Series" subtitle for cards and hero meta lines. */
@Composable
internal fun typeName(mediaType: MediaType): String =
    stringResource(if (mediaType == MediaType.TV) R.string.type_series else R.string.type_movie)

/**
 * One catalog poster: 2:3 artwork with title + year, opening detail on CENTER.
 * Focusing the card reports it so a hero can follow the focus.
 */
@Composable
internal fun MediaPosterCard(
    item: MediaItem,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 180.dp,
    onFocus: (() -> Unit)? = null,
) {
    TvMediaCard(
        onClick = onOpen,
        image = {
            AsyncImage(
                model = TmdbImageUrl.poster(item.posterPath),
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        },
        modifier = modifier,
        width = width,
        aspectRatio = TvAspect.Poster,
        title = item.title,
        subtitle = item.year?.toString(),
        onFocus = onFocus,
    )
}

/**
 * One continue-watching tile: landscape card with resume bar and S/E badge,
 * resuming playback on CENTER.
 */
@Composable
internal fun ContinueEntryCard(
    entry: ContinueWatchingEntry,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: (() -> Unit)? = null,
) {
    TvMediaCard(
        onClick = onResume,
        image = {
            AsyncImage(
                model = TmdbImageUrl.poster(entry.posterPath),
                contentDescription = entry.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        },
        modifier = modifier,
        width = 320.dp,
        aspectRatio = TvAspect.Landscape,
        title = entry.title,
        progress = resumeProgress(entry.positionMs, entry.durationMs),
        badge = if (entry.season > 0 || entry.episode > 0) {
            stringResource(R.string.resume_badge, entry.season, entry.episode)
        } else {
            null
        },
        onFocus = onFocus,
    )
}

/**
 * One episode: landscape still with the episode-number badge, playing on CENTER.
 */
@Composable
internal fun EpisodeStillCard(
    episode: Episode,
    progress: Float?,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvMediaCard(
        onClick = onPlay,
        image = {
            AsyncImage(
                model = TmdbImageUrl.still(episode.stillPath),
                contentDescription = episode.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        },
        modifier = modifier,
        width = 320.dp,
        aspectRatio = TvAspect.Landscape,
        title = episode.name,
        subtitle = episode.overview.ifBlank { null },
        progress = progress,
        badge = stringResource(R.string.episode_badge, episode.episodeNumber),
    )
}
