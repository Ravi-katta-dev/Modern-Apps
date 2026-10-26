package com.vayunmathur.uniflix.ui.tv

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.tv.TvInfoCard
import com.vayunmathur.uniflix.R
import com.vayunmathur.uniflix.data.TmdbImageUrl
import com.vayunmathur.uniflix.domain.Episode

/**
 * One episode as a full-information card: the 16:9 still as leading art,
 * "E3 · Title", the overview capped at 2 lines, and the resume bar.
 * Fills its grid cell — laid out 2 across on the detail page.
 */
@Composable
internal fun TvEpisodeCard(
    episode: Episode,
    progress: Float?,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvInfoCard(
        title = stringResource(
            R.string.episode_title,
            episode.episodeNumber,
            episode.name,
        ),
        onClick = onPlay,
        modifier = modifier,
        description = episode.overview.ifBlank { null },
        descriptionLines = 2,
        leading = {
            AsyncImage(
                model = TmdbImageUrl.still(episode.stillPath),
                contentDescription = episode.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        },
        leadingSize = 112.dp,
        progress = progress,
    )
}
