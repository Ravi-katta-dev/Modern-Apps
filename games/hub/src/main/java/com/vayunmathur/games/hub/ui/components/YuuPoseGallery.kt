package com.vayunmathur.games.hub.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.hub.ui.yuu.YUU_POSES
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.Text

/**
 * Preset pose gallery: one chip per 3D pose preset (idle, wave, thumbs up,
 * thinking, cheering). Stateless — selection writes back through the
 * caller's draft or profile update.
 */
@Composable
fun YuuPoseGallery(
    selectedPoseId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(YUU_POSES) { pose ->
            FilterChip(
                selected = (selectedPoseId ?: "idle") == pose.id,
                onClick = { onSelect(pose.id) },
                label = { Text(pose.name) },
            )
        }
    }
}
