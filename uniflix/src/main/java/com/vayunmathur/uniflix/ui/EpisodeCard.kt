package com.vayunmathur.uniflix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.uniflix.data.TmdbImageUrl
import com.vayunmathur.uniflix.domain.Episode

/**
 * One episode: the still fills the card, the title sits over it on a
 * translucent scrim for contrast. The whole card is the play affordance.
 */
@Composable
fun EpisodeCard(episode: Episode, onPlay: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay)) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            AsyncImage(
                model = TmdbImageUrl.still(episode.stillPath),
                contentDescription = episode.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Column(
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f)).padding(8.dp),
            ) {
                Text(
                    "E${episode.episodeNumber} · ${episode.name}",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                )
                if (episode.overview.isNotBlank()) {
                    Text(
                        episode.overview,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 2,
                    )
                }
            }
        }
    }
}
