package com.vayunmathur.youpipe.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvRowCard
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.tv.TvShelf
import com.vayunmathur.library.tv.tvContentPadding
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconList
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.util.YouPipeViewModel

/**
 * TV saved library: Watch later + Downloads as row cards, then user playlists as a grid of
 * [TvRowCard]s with counts. Creating a playlist is the header action; deleting/renaming
 * happens on the playlist detail screen.
 *
 * The phone reorders playlists with a drag handle; a D-pad has no drag, and stacked
 * per-row move buttons break the one-focus-axis rule — so reorder stays phone-only.
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

    TvInitialFocus(firstFocus)
    Column(Modifier.fillMaxSize()) {
        TvScreenHeader(
            title = stringResource(UiR.string.saved),
            actions = {
                TvButton(
                    text = stringResource(R.string.new_playlist),
                    onClick = { backStack.add(TvRoute.CreatePlaylist) },
                    icon = { IconAdd() },
                    modifier = Modifier.focusRequester(firstFocus),
                )
            },
        )
        TvShelf(title = null) {
            item(key = "pinned-downloads") {
                TvRowCard(
                    title = stringResource(R.string.title_downloads),
                    subtitle = stringResource(R.string.playlist_video_count, downloadedVideos.size),
                    leading = { IconDownload() },
                    onClick = { backStack.add(TvRoute.Downloads) },
                )
            }
            if (watchLater != null) {
                item(key = "pinned-watch-later") {
                    TvRowCard(
                        title = stringResource(R.string.playlist_watch_later),
                        subtitle = stringResource(
                            R.string.playlist_video_count,
                            counts[watchLater.id] ?: 0,
                        ),
                        leading = { IconList() },
                        onClick = { backStack.add(TvRoute.PlaylistDetail(watchLater.id)) },
                    )
                }
            }
        }
        if (userPlaylists.isEmpty()) {
            TvEmptyState(title = stringResource(R.string.playlist_empty))
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = rememberLazyGridState(),
            modifier = Modifier.fillMaxSize().padding(top = 8.dp),
            contentPadding = tvContentPadding(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(userPlaylists, key = { it.id }) { playlist ->
                TvRowCard(
                    title = playlist.name,
                    subtitle = stringResource(R.string.playlist_video_count, counts[playlist.id] ?: 0),
                    leading = { IconList() },
                    width = 420.dp,
                    onClick = { backStack.add(TvRoute.PlaylistDetail(playlist.id)) },
                )
            }
        }
    }
}
