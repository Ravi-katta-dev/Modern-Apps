package com.vayunmathur.uniflix.ui.tv

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.tv.TvAspect
import com.vayunmathur.library.tv.TvMediaCard
import com.vayunmathur.uniflix.R
import com.vayunmathur.uniflix.data.ContinueWatchingEntry
import com.vayunmathur.uniflix.data.TmdbImageUrl
import com.vayunmathur.uniflix.data.WatchlistEntry
import com.vayunmathur.uniflix.domain.MediaItem
import com.vayunmathur.uniflix.domain.MediaType

/** A resume entry as catalog item, for shared card rendering. */
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

/** Stable key for lazy-grid items. */
internal fun mediaKey(item: MediaItem): String = "${item.mediaType}:${item.tmdbId}"

/** 0..1 resume fraction, or null when there is nothing meaningful to draw. */
internal fun resumeProgress(positionMs: Long, durationMs: Long): Float? =
    if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else null

/** "Movie"/"Series" subtitle for cards and meta lines. */
@Composable
internal fun typeName(mediaType: MediaType): String =
    stringResource(if (mediaType == MediaType.TV) R.string.type_series else R.string.type_movie)

/** Attaches [firstFocus] to the grid's first item so initial focus lands on content. */
internal fun Modifier.focusIf(key: String, firstKey: String?, firstFocus: FocusRequester): Modifier =
    if (key == firstKey) focusRequester(firstFocus) else this

/**
 * One catalog poster: 2:3 artwork with title + year, opening detail on CENTER.
 * Fills its grid cell — column counts come from the owning [TvGrid].
 */
@Composable
internal fun MediaPosterCard(
    item: MediaItem,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
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
        aspectRatio = TvAspect.Poster,
        title = item.title,
        subtitle = item.year?.toString(),
        onLongClick = onLongClick,
    )
}

/**
 * One continue-watching card: landscape artwork with resume bar and S/E badge,
 * resuming playback on CENTER. Fills its grid cell — span 2 of 6 on home so
 * three landscape cards sit in a row.
 */
@Composable
internal fun ContinueEntryCard(
    entry: ContinueWatchingEntry,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
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
        aspectRatio = TvAspect.Landscape,
        title = entry.title,
        progress = resumeProgress(entry.positionMs, entry.durationMs),
        badge = if (entry.season > 0 || entry.episode > 0) {
            stringResource(R.string.resume_badge, entry.season, entry.episode)
        } else {
            null
        },
    )
}
