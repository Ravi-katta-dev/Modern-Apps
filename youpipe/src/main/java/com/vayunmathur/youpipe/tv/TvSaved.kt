package com.vayunmathur.youpipe.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconList
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.util.YouPipeViewModel

/**
 * TV saved library: pinned Downloads + Watch later rows, then user playlists.
 *
 * The phone reorders playlists with a drag handle; a D-pad has no drag. Reorder is a
 * move-mode instead: focusing a playlist's move button arms "move this", UP/DOWN re-targets
 * the focused row, CENTER commits (positions recomputed with the same midpoint scheme the
 * phone's `onMove` uses). One focus axis throughout — nothing nests.
 */
@Composable
fun TvSavedPage(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
) {
    val playlists by ypvm.playlists.collectAsStateWithLifecycle()
    val downloadedVideos by ypvm.downloadedVideos.collectAsStateWithLifecycle()
    val allPlaylistItems by ypvm.allPlaylistItems.collectAsStateWithLifecycle()
    val counts = remember(allPlaylistItems) { allPlaylistItems.groupingBy { it.playlistId }.eachCount() }
    val watchLater = playlists.firstOrNull { it.mandatory }
    val userPlaylists = playlists.filter { !it.mandatory }
    val firstFocus = remember { FocusRequester() }

    // Move-mode: the playlist being moved, or null when browsing normally.
    var movingId by remember { mutableStateOf<Long?>(null) }

    TvInitialFocus(firstFocus)
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "saved-title") {
            Text(text = stringResource(UiR.string.saved), style = MaterialTheme.typography.headlineSmall)
        }
        item(key = "pinned-downloads") {
            Surface(
                onClick = { backStack.add(TvRoute.Downloads) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(firstFocus),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconDownload()
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(text = stringResource(R.string.title_downloads), style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = stringResource(R.string.playlist_video_count, downloadedVideos.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (watchLater != null) {
            item(key = "pinned-watch-later") {
                Surface(
                    onClick = { backStack.add(TvRoute.PlaylistDetail(watchLater.id)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconList()
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(text = stringResource(R.string.playlist_watch_later), style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = stringResource(R.string.playlist_video_count, counts[watchLater.id] ?: 0),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        items(userPlaylists, key = { it.id }) { playlist ->
            val isMoving = movingId == playlist.id
            Surface(
                onClick = {
                    // CENTER commits a pending move onto this row, else opens the playlist.
                    val moveFrom = movingId
                    if (moveFrom != null && moveFrom != playlist.id) {
                        commitTvPlaylistMove(ypvm, userPlaylists, moveFrom, playlist.id)
                        movingId = null
                    } else {
                        backStack.add(TvRoute.PlaylistDetail(playlist.id))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconList()
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(text = playlist.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = stringResource(R.string.playlist_video_count, counts[playlist.id] ?: 0),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (isMoving) {
                            Text(
                                text = stringResource(R.string.tv_move_mode_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    if (userPlaylists.size > 1) {
                        if (isMoving) {
                            Button(onClick = { movingId = null }) {
                                Text(stringResource(R.string.action_close))
                            }
                        } else {
                            Button(onClick = { movingId = playlist.id }) {
                                Text(stringResource(R.string.tv_action_move))
                            }
                        }
                    }
                }
            }
        }
        item(key = "new-playlist") {
            Button(onClick = { backStack.add(TvRoute.CreatePlaylist) }) {
                IconAdd()
            }
        }
    }
}

/**
 * Commit a move-mode reorder: the dragged playlist takes the midpoint position between its
 * new neighbours — the same position scheme as the phone's drag `onMove`.
 */
private fun commitTvPlaylistMove(
    ypvm: YouPipeViewModel,
    ordered: List<com.vayunmathur.youpipe.data.Playlist>,
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
    ypvm.playlistOps.reorderPlaylists(repositioned)
}
