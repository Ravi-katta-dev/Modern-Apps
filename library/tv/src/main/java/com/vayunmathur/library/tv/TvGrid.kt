package com.vayunmathur.library.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * The screen body: one vertically scrolling grid. Phone lists become grids of full-information
 * cards ([TvInfoCard]); phone sections become [tvGridHeader]s spanning the full width. Use
 * [GridCells.Fixed] (3 for info cards, 4-5 for thumbnails, 6-7 for posters).
 */
@Composable
fun TvGrid(
    columns: Int,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    content: LazyGridScope.() -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = tvContentPadding(top = TvDimens.SafeVertical),
        horizontalArrangement = Arrangement.spacedBy(TvDimens.ItemGap),
        verticalArrangement = Arrangement.spacedBy(TvDimens.ItemGap),
        content = content,
    )
}

/** Something that spans the whole grid row: a banner, a chip row, a header. */
fun LazyGridScope.tvFullWidth(key: Any, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = "full-width") { content() }
}

/**
 * A section header spanning the grid: title, optional one-line subtitle, optional trailing
 * action (typically a "Browse"/"See all" [TvButton]). Mirrors the phone's section headers.
 */
fun LazyGridScope.tvGridHeader(
    key: Any,
    title: String,
    subtitle: String? = null,
    first: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
        Row(
            Modifier.fillMaxWidth().padding(top = if (first) 0.dp else TvDimens.ShelfGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (action != null) action()
        }
    }
}
