package com.vayunmathur.library.tv

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * Full-bleed artwork behind a screen, cross-fading when [key] changes (e.g. the focused card),
 * with scrims that fade it into the background on the start edge and bottom so text over it
 * stays legible. [image] draws for a given key; draw nothing to show only the background.
 */
@Composable
fun <K> TvBackdrop(
    key: K,
    modifier: Modifier = Modifier,
    image: @Composable BoxScope.(K) -> Unit,
) {
    val background = MaterialTheme.colorScheme.background
    Box(modifier.fillMaxSize()) {
        Crossfade(targetState = key, animationSpec = tween(BACKDROP_FADE_MS), label = "tv-backdrop") { k ->
            Box(Modifier.fillMaxSize()) { image(k) }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to background,
                    0.35f to background.copy(alpha = 0.85f),
                    0.7f to Color.Transparent,
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.55f to background.copy(alpha = 0.35f),
                    1f to background,
                ),
            ),
        )
    }
}

/**
 * Hero text block for a featured item or a detail page: eyebrow, big title, a meta line,
 * a few lines of description and an action row. The text is sized to the left ~half of the
 * screen so the backdrop art stays visible; the action row spans the full width and scrolls.
 */
@Composable
fun TvHeroText(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    meta: List<String> = emptyList(),
    description: String? = null,
    descriptionLines: Int = 3,
    chips: (@Composable RowScope.() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.fillMaxWidth(0.52f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (eyebrow != null) {
                Text(
                    text = eyebrow.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.primary,
                    maxLines = 1,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.displaySmall,
                color = scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotEmpty()) {
                Text(
                    text = meta.filter { it.isNotBlank() }.joinToString("  •  "),
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (chips != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    chips()
                }
            }
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurface.copy(alpha = 0.85f),
                    maxLines = descriptionLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (actions != null) {
            // Full width and scrollable, not confined to the text column: detail pages carry many
            // actions and a squeezed button is one the D-pad can't reach.
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 6.dp, bottom = 6.dp, end = TvDimens.SafeHorizontal),
                content = actions,
            )
        }
    }
}

private const val BACKDROP_FADE_MS = 450
