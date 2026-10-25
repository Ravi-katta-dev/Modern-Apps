package com.vayunmathur.uniflix.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvDialog
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvListRow
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.uniflix.R
import com.vayunmathur.uniflix.domain.SubtitleTrack

/**
 * A stream/source picker over the player: a focusable list of rows plus
 * Cancel. Pickers are dialogs on TV — no bottom sheets ten feet away.
 */
@Composable
fun TvPickerDialog(title: String, onDismiss: () -> Unit, rows: LazyListScope.() -> Unit) {
    val rowFocus = remember { FocusRequester() }
    TvInitialFocus(rowFocus, key = title)
    TvDialog(
        title = title,
        onDismissRequest = onDismiss,
        content = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                rows()
            }
        },
        buttons = {
            TvButton(text = stringResource(UiR.string.cancel), onClick = onDismiss)
        },
    )
}

/** ABR-ladder label for a rendition height, mirroring the phone's labels. */
internal fun resolutionLabel(height: Int): String = when {
    height >= 2100 -> "4K"
    height >= 1000 -> "1080p"
    height >= 680 -> "720p"
    height >= 460 -> "480p"
    else -> "${height}p"
}

/** Server picker: one row per healthy server, the current one checked. */
@Composable
internal fun TvServerPickerDialog(
    servers: List<String>,
    selectedTitle: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    TvPickerDialog(
        title = stringResource(R.string.server),
        onDismiss = onDismiss,
        rows = {
            items(servers, key = { "server-$it" }) { server ->
                TvListRow(
                    headline = server,
                    selected = server.equals(selectedTitle ?: "", ignoreCase = true),
                    onClick = { onSelect(server) },
                )
            }
        },
    )
}

/** Subtitle picker: an Off row plus one row per sidecar track. */
@Composable
internal fun TvSubtitlePickerDialog(
    subtitles: List<SubtitleTrack>,
    selected: SubtitleTrack?,
    onDismiss: () -> Unit,
    onSelect: (SubtitleTrack?) -> Unit,
) {
    TvPickerDialog(
        title = stringResource(R.string.subtitles_cc),
        onDismiss = onDismiss,
        rows = {
            items(listOf(Unit), key = { "sub-off" }) {
                TvListRow(
                    headline = stringResource(R.string.subtitles_off),
                    selected = selected == null,
                    onClick = { onSelect(null) },
                )
            }
            items(subtitles, key = { "sub-${it.url}" }) { sub ->
                TvListRow(
                    headline = sub.displayName.ifBlank { sub.language },
                    selected = selected?.url == sub.url,
                    onClick = { onSelect(sub) },
                )
            }
        },
    )
}

/** Quality picker: Auto plus one row per rendition height. */
@Composable
internal fun TvQualityPickerDialog(
    heights: List<Int>,
    selectedHeight: Int?,
    onDismiss: () -> Unit,
    onSelect: (Int?) -> Unit,
) {
    TvPickerDialog(
        title = stringResource(R.string.quality),
        onDismiss = onDismiss,
        rows = {
            items(listOf(Unit), key = { "q-auto" }) {
                TvListRow(
                    headline = stringResource(R.string.auto),
                    selected = selectedHeight == null,
                    onClick = { onSelect(null) },
                )
            }
            items(heights, key = { "q-$it" }) { height ->
                TvListRow(
                    headline = resolutionLabel(height),
                    selected = selectedHeight == height,
                    onClick = { onSelect(height) },
                )
            }
        },
    )
}
