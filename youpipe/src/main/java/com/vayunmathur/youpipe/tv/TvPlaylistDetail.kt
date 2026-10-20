package com.vayunmathur.youpipe.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Checkbox
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.ui.videoRowState
import com.vayunmathur.youpipe.util.YouPipeViewModel

/**
 * TV playlist detail: videos as a focusable grid with D-pad multiselect + move-mode reorder.
 *
 * The phone combines long-press selection with drag-handle reorder. Neither exists on a TV:
 * selection is a checkbox on each card (same pattern as [TvHistoryPage]), and reorder is the
 * same move-mode as [TvSavedPage] — arm on one row, commit onto another, midpoint positions.
 * The whole playlist deletes from a focused action row (Watch later excluded, as on phone).
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
    val context = LocalContext.current
    val firstFocus = remember { FocusRequester() }

    val mandatory = playlist?.mandatory == true
    val title = when {
        mandatory -> stringResource(R.string.playlist_watch_later)
        else -> playlist?.name.orEmpty()
    }

    val selectedIds = remember { mutableStateListOf<Long>() }
    val isSelectionMode by remember { derivedStateOf { selectedIds.isNotEmpty() } }
    BackHandler(enabled = isSelectionMode) { selectedIds.clear() }

    var movingId by remember { mutableStateOf<Long?>(null) }

    TvInitialFocus(firstFocus)
    val gridState = rememberLazyGridState()
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        Text(
            text = if (isSelectionMode) {
                stringResource(R.string.selected_count, selectedIds.size)
            } else {
                title
            },
            style = MaterialTheme.typography.headlineSmall,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            if (isSelectionMode) {
                Button(
                    onClick = {
                        playlistItems.filter { it.id in selectedIds }
                            .forEach { ypvm.playlistOps.removeFromPlaylist(it) }
                        selectedIds.clear()
                    },
                ) {
                    IconDelete()
                }
                Button(onClick = { selectedIds.clear() }) {
                    IconClose()
                }
            } else if (!mandatory) {
                Button(
                    onClick = {
                        playlist?.let { ypvm.playlistOps.deletePlaylist(it) }
                        backStack.pop()
                    },
                ) {
                    IconDelete()
                }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(playlistItems, key = { it.id }) { item ->
                val isSelected = item.id in selectedIds
                val isMoving = movingId == item.id
                Column {
                    TvVideoCard(
                        row = videoRowState(
                            context = context,
                            videoInfo = item.videoItem,
                            showAuthor = true,
                        ),
                        onOpen = {
                            val moveFrom = movingId
                            when {
                                moveFrom != null && moveFrom != item.id -> {
                                    commitTvPlaylistItemMove(ypvm, playlistItems, moveFrom, item.id)
                                    movingId = null
                                }
                                isSelectionMode -> {
                                    if (isSelected) selectedIds.remove(item.id)
                                    else selectedIds.add(item.id)
                                }
                                else -> backStack.add(TvRoute.Video(item.videoItem.videoID))
                            }
                        },
                        modifier = if (playlistItems.indexOf(item) == 0) {
                            Modifier.focusRequester(firstFocus)
                        } else {
                            Modifier
                        },
                        trailingContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 8.dp),
                            ) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = {
                                        if (isSelected) selectedIds.remove(item.id)
                                        else selectedIds.add(item.id)
                                    },
                                )
                                if (playlistItems.size > 1) {
                                    Button(
                                        onClick = {
                                            movingId = if (isMoving) null else item.id
                                        },
                                        modifier = Modifier.padding(start = 8.dp),
                                    ) {
                                        Text(
                                            if (isMoving) {
                                                stringResource(R.string.action_close)
                                            } else {
                                                stringResource(R.string.tv_action_move)
                                            },
                                        )
                                    }
                                }
                            }
                            if (isMoving) {
                                Text(
                                    text = stringResource(R.string.tv_move_mode_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Midpoint-position move commit for playlist items — same scheme as the phone's drag onMove. */
private fun commitTvPlaylistItemMove(
    ypvm: YouPipeViewModel,
    ordered: List<com.vayunmathur.youpipe.data.PlaylistItem>,
    fromId: Long,
    toId: Long,
) {
    val fromIdx = ordered.indexOfFirst { it.id == fromId }
    val toIdx = ordered.indexOfFirst { it.id == toId }
    if (fromIdx !in ordered.indices || toIdx !in ordered.indices || fromIdx == toIdx) return
    val mutable = ordered.toMutableList()
    val moved = mutable.removeAt(fromIdx)
    mutable.add(toIdx, moved)
    val prevPos = mutable.getOrNull(toIdx - 1)?.position
    val nextPos = mutable.getOrNull(toIdx + 1)?.position
    val resultPosition = when {
        prevPos == null -> (nextPos ?: 0.0) - 50.0
        nextPos == null -> prevPos + 50.0
        else -> (prevPos + nextPos) / 2.0
    }
    val repositioned = mutable.toMutableList()
    repositioned[toIdx] = moved.copy(position = resultPosition)
    ypvm.playlistOps.reorderPlaylistItems(repositioned)
}
