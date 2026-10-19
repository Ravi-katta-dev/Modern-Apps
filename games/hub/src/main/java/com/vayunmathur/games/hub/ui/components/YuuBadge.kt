package com.vayunmathur.games.hub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.hub.data.entities.YuuFaceConfig
import com.vayunmathur.library.ui.MaterialTheme

/**
 * Circular Yuu miniature for profile headers and the plaza grid. Same sizing and
 * surface as [AvatarBadge] so the two are interchangeable in header rows.
 */
@Composable
fun YuuBadge(config: YuuFaceConfig, modifier: Modifier = Modifier, large: Boolean = false) {
    val size = if (large) 56.dp else 36.dp
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        YuuFace(
            config = config,
            modifier = Modifier.size(size - 8.dp).padding(2.dp),
        )
    }
}
