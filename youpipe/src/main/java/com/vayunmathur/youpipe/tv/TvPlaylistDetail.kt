package com.vayunmathur.youpipe.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvDialog
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.tv.tvContentPadding
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.util.YouPipeViewModel

/**
 * TV playlist detail: header (title, count, play-all, delete) + a 4-column grid of
 * [TvSelectableCard]s with D-pad multiselect for removal.
 *
 * The phone combines long-press selection with drag-handle reorder. Neither exists on a
 * TV: CENTER opens, long-press toggles selection as a fallback, and removal is a header
 * button. Reorder stays phone-only (same rationale as [TvSavedPage]).
 */
@Composable
fun TvPlaylistDetailPage(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
    playlistId: Long,
) {
    val playlist by remember(playlistId) { ypvm.playlistById(playlistId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val itemsFlow = remember(playlistId) { ypvm.playlistItemsFor(playlistId) }
    val playlistItems by itemsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val history by ypvm.historyVideos.collectAsState()
    val historyById = remember(history) { history.associate { it.id to it.progress } }

    val mandatory = playlist?.mandatory == true
    val title = when {
        mandatory -> stringResource(R.string.playlist_watch_later)
        else -> playlist?.name.orEmpty()
    }

    val selectedIds = remember { mutableStateListOf<Long>() }
    val isSelectionMode by remember { derivedStateOf { selectedIds.isNotEmpty() } }
    BackHandler(enabled = isSelectionMode) { selectedIds.clear() }

    var confirmDelete by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    TvInitialFocus(firstFocus)

    val rows = rememberTvVideoRows(
        videos = remember(playlistItems) { playlistItems.map { it.videoItem } },
        ypvm = ypvm,
        historyProgressMsById = historyById,
    )
    val rowByVideoId = remember(rows) { rows.associateBy { it.row.videoID } }

    Column(Modifier.fillMaxSize()) {
        TvScreenHeader(
            title = if (isSelectionMode) {
                stringResource(R.string.selected_count, selectedIds.size.toString())
            } else {
                title
            },
            subtitle = stringResource(R.string.playlist_video_count, playlistItems.size),
            actions = {
                if (isSelectionMode) {
                    TvButton(
                        text = stringResource(UiR.string.delete),
                        onClick = {
                            playlistItems.filter { it.id in selectedIds }
                                .forEach { ypvm.playlistOps.removeFromPlaylist(it) }
                            selectedIds.clear()
                        },
                        icon = { IconDelete() },
                    )
                    TvButton(
                        text = stringResource(UiR.string.cancel),
                        onClick = { selectedIds.clear() },
                    )
                } else {
                    if (playlistItems.isNotEmpty()) {
                        TvButton(
                            text = stringResource(UiR.string.play),
                            onClick = {
                                backStack.add(TvRoute.Video(playlistItems.first().videoItem.videoID))
                            },
                            icon = { IconPlay() },
                            primary = true,
                            modifier = Modifier.focusRequester(firstFocus),
                        )
                    }
                    if (!mandatory && playlist != null) {
                        TvButton(
                            text = stringResource(R.string.action_delete_playlist),
                            onClick = { confirmDelete = true },
                            icon = { IconDelete() },
                        )
                    }
                }
            },
        )
        if (rows.isEmpty()) {
            TvEmptyState(title = stringResource(R.string.playlist_empty))
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(TV_GRID_COLUMNS),
            state = rememberLazyGridState(),
            modifier = Modifier.fillMaxSize().padding(top = 8.dp),
            contentPadding = tvContentPadding(),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(28.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(20.dp),
        ) {
            items(playlistItems.size, key = { playlistItems[it].id }) { index ->
                val item = playlistItems[index]
                val row = rowByVideoId[item.videoItem.videoID]
                if (row != null) {
                    val isSelected = item.id in selectedIds
                    TvSelectableCard(
                        item = row,
                        selected = isSelected,
                        inSelectionMode = isSelectionMode,
                        onOpen = { backStack.add(TvRoute.Video(item.videoItem.videoID)) },
                        onToggleSelect = {
                            if (isSelected) selectedIds.remove(item.id)
                            else selectedIds.add(item.id)
                        },
                    )
                }
            }
        }
    }
    if (confirmDelete) {
        TvDialog(
            title = stringResource(R.string.action_delete_playlist),
            onDismissRequest = { confirmDelete = false },
            body = title,
            buttons = {
                TvButton(
                    text = stringResource(UiR.string.delete),
                    onClick = {
                        playlist?.let { ypvm.playlistOps.deletePlaylist(it) }
                        confirmDelete = false
                        backStack.pop()
                    },
                    primary = true,
                )
                TvButton(
                    text = stringResource(UiR.string.cancel),
                    onClick = { confirmDelete = false },
                )
            },
            content = {},
        )
    }
}
