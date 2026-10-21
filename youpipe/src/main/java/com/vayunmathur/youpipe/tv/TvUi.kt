package com.vayunmathur.youpipe.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconSkipPrevious
import com.vayunmathur.youpipe.util.VideoRowState
import kotlinx.coroutines.delay

/**
 * Shared D-pad UI primitives for every TV screen.
 *
 * Everything here exists because `androidx.tv.material` rows are focus-first: each item is a
 * focusable [Surface] with a visible focused border, and lists restore focus to the last-focused
 * index after data changes. Nothing here is clickable-without-focus — a D-pad has no pointer —
 * so there are no `invisibleClickable` wrappers anywhere in `tv/`.
 */

/** Request focus once composed, so the D-pad has somewhere to start on every screen. */
@Composable
internal fun TvInitialFocus(requester: FocusRequester) {
    LaunchedEffect(requester) {
        // One frame late: requesting during composition races the focus system on first layout
        // and silently drops, leaving the D-pad dead until the user presses something.
        delay(FRAME_DELAY_MS)
        runCatching { requester.requestFocus() }
    }
}

/**
 * Remember which item holds focus, so a list rebuild (refresh, delete, reorder) returns the
 * D-pad to the same row instead of dropping it back at the top.
 */
@Composable
internal fun rememberTvFocusIndex(): MutableIntState = remember { mutableIntStateOf(0) }

/**
 * One video row for every TV list: a focusable card with a 16:9 thumbnail, title, byline and
 * resume bar. The whole card is the focus target — CENTER/ENTER opens the video — and an
 * optional trailing action row (checkbox, overflow) is reached with RIGHT rather than DOWN,
 * so the list keeps one focus axis.
 *
 * Thumbnails reuse the phone's AsyncImage + memory-cache keys verbatim; only the frame is new.
 *
 * @param focused whether this row currently holds D-pad focus; drives the highlighted frame.
 * @param onFocusChanged reports focus gain so the parent list can record the index for restore.
 */
@Composable
internal fun TvVideoCard(
    row: VideoRowState,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    onFocusChanged: (Boolean) -> Unit = {},
    trailingContent: @Composable (() -> Unit)? = null,
) {
    val context = LocalContext.current
    Surface(
        onClick = onOpen,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { onFocusChanged(it.isFocused) },
    ) {
        Column(Modifier.padding(16.dp)) {
            Box {
                if (row.thumbnailURL.isNotEmpty()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(row.thumbnailURL)
                            .memoryCacheKey("video-thumb-${row.videoID}-${if (row.deArrowThumbnail) "da" else "yt"}")
                            .build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Crop,
                    )
                }
                if (row.percentWatched > 0f) {
                    TvResumeBar(row.percentWatched, Modifier.align(Alignment.BottomCenter))
                }
            }
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp),
            )
            val author = row.author
            if (author != null) {
                Text(
                    text = author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (row.stats.isNotEmpty()) {
                Text(
                    text = row.stats,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            trailingContent?.invoke()
        }
    }
}

/** The red resume bar across the bottom of a thumbnail. Same 0f..1f contract as the phone row. */
@Composable
internal fun TvResumeBar(percentWatched: Float, modifier: Modifier = Modifier) {
    val clamped = percentWatched.coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth(),
    ) {
        Box(
            Modifier
                .fillMaxWidth(clamped)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/**
 * A D-pad list screen: title, optional action row, and a focus-restoring [LazyColumn].
 *
 * The list records the focused index and scrolls it back into view after data changes, so a
 * delete or refresh never strands the D-pad at the top of a long list.
 */
@Composable
internal fun TvListScreen(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable (() -> Unit)? = null,
    emptyMessage: String? = null,
    itemCount: Int = 0,
    content: @Composable (LazyListState) -> Unit,
) {
    val listState = rememberLazyListState()
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        actions?.let {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 12.dp),
            ) { it() }
        }
        if (emptyMessage != null && itemCount == 0) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = emptyMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            content(listState)
        }
    }
}

/** One focusable settings row: title plus optional summary, CENTER/ENTER activates. */
@Composable
internal fun TvSettingsRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
) {
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** A focusable switch row: the whole row toggles, so the D-pad never has to land on a thumb. */
@Composable
internal fun TvSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
) {
    Surface(onClick = { onCheckedChange(!checked) }, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleLarge)
                if (summary != null) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

private const val FRAME_DELAY_MS = 16L

internal fun tvIconForTab(tab: Int): @Composable () -> Unit = when (tab) {
    TV_TAB_HOME -> ({ IconSearch() })
    TV_TAB_SUBSCRIPTIONS -> ({ IconCheck() })
    TV_TAB_HISTORY -> ({ IconSkipPrevious() })
    TV_TAB_SAVED -> ({ IconDownload() })
    else -> ({ IconSettings() })
}

internal fun tvLabelForTab(tab: Int, home: String, subscriptions: String, history: String, saved: String, settings: String): String =
    when (tab) {
        TV_TAB_HOME -> home
        TV_TAB_SUBSCRIPTIONS -> subscriptions
        TV_TAB_HISTORY -> history
        TV_TAB_SAVED -> saved
        else -> settings
    }
