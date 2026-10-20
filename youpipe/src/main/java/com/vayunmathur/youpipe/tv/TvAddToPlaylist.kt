package com.vayunmathur.youpipe.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Checkbox
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.util.YouPipeViewModel

/**
 * TV add-to-playlist: staged membership checkboxes as a focusable list.
 *
 * The phone stages membership in an `AddToListDialog` and applies on OK. Same staging here —
 * each playlist row is a focusable `Surface` with a checkbox (CENTER toggles membership), and
 * the OK/Cancel rows at the bottom apply or drop the stage. BACK dismisses without applying,
 * via the overlay-dismiss-first rule. The "new playlist" field only opens its text entry on
 * explicit focus + IME action, never autofocused.
 */
@Composable
fun TvAddToPlaylistPage(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
    videoID: Long,
    includeWatchLater: Boolean = true,
) {
    val allPlaylists by ypvm.playlists.collectAsStateWithLifecycle()
    val playlists = if (includeWatchLater) allPlaylists else allPlaylists.filter { !it.mandatory }
    val allItems by ypvm.allPlaylistItems.collectAsStateWithLifecycle()
    val videoState by ypvm.videoState.collectAsStateWithLifecycle()
    val closeFocus = remember { FocusRequester() }

    val data = videoState.data
    if (data == null) {
        // Same fallback as the phone dialog: the video state went away (rotation, process
        // death) so there is nothing to add — back out instead of staging against nothing.
        TvInitialFocus(closeFocus)
        Column(
            Modifier
                .fillMaxSize()
                .padding(48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = stringResource(R.string.add_to_playlist), style = MaterialTheme.typography.headlineSmall)
            Button(onClick = { backStack.pop() }, modifier = Modifier.focusRequester(closeFocus)) {
                Text(stringResource(R.string.action_close))
            }
        }
        return
    }

    val video = VideoInfo(
        data.title, videoID, data.duration, data.views, data.uploadDate, data.thumbnailURL, data.author,
    )
    val membership = remember(allItems, videoID) {
        allItems.filter { it.videoItem.videoID == videoID }.map { it.playlistId }.toSet()
    }
    var staged by remember(membership) { mutableStateOf(membership) }
    var newName by remember { mutableStateOf("") }
    var nameOpen by remember { mutableStateOf(false) }
    val watchLaterLabel = stringResource(R.string.playlist_watch_later)

    fun confirm() {
        val selectedIds = staged
        playlists.forEach { playlist ->
            val wasMember = playlist.id in membership
            val nowMember = playlist.id in selectedIds
            if (nowMember && !wasMember) {
                ypvm.playlistOps.addVideoToPlaylist(playlist.id, video)
            } else if (!nowMember && wasMember) {
                allItems.firstOrNull {
                    it.playlistId == playlist.id && it.videoItem.videoID == videoID
                }?.let { ypvm.playlistOps.removeFromPlaylist(it) }
            }
        }
        backStack.pop()
    }

    TvInitialFocus(closeFocus)
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        Text(text = stringResource(R.string.add_to_playlist), style = MaterialTheme.typography.headlineSmall)
        LazyColumn(
            modifier = Modifier.weight(1f).padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(playlists, key = { it.id }) { playlist ->
                val checked = playlist.id in staged
                Surface(
                    onClick = {
                        staged = if (checked) staged - playlist.id else staged + playlist.id
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Text(
                            text = if (playlist.mandatory) watchLaterLabel else playlist.name,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
            item(key = "new-playlist") {
                Surface(
                    onClick = { nameOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(text = stringResource(R.string.new_playlist), style = MaterialTheme.typography.titleMedium)
                        if (nameOpen) {
                            androidx.compose.foundation.text.BasicTextField(
                                value = newName,
                                onValueChange = { newName = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                textStyle = MaterialTheme.typography.titleMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                            )
                            val canCreate = newName.isNotBlank() &&
                                allPlaylists.none { it.name == newName.trim() }
                            Button(
                                onClick = {
                                    ypvm.playlistOps.createPlaylistAndAddVideo(newName.trim(), video)
                                    backStack.pop()
                                },
                                enabled = canCreate,
                                modifier = Modifier.padding(top = 8.dp),
                            ) {
                                Text(stringResource(R.string.new_playlist))
                            }
                        }
                    }
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Button(onClick = ::confirm) {
                Text(stringResource(android.R.string.ok))
            }
            Button(onClick = { backStack.pop() }, modifier = Modifier.focusRequester(closeFocus)) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    }
}
