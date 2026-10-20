package com.vayunmathur.games.hub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

/**
 * Neutral stand-in shown where a 3D Yuu surface cannot draw (native library
 * absent, both GPU backends failed, over the live-surface budget, or
 * Layoutlib previews which cannot render Vulkan). Blank box + the Yuu's
 * initial — 3D is the only avatar renderer.
 *
 * Sizing belongs to the caller: no fill modifier here, so aspect ratios set
 * outside (e.g. plaza cells) are honored.
 */
@Composable
fun YuuPlaceholder(
    name: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.firstOrNull()?.uppercase() ?: "…",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
