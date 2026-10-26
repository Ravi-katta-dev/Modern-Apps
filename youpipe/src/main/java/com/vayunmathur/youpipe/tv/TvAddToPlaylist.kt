package com.vayunmathur.youpipe.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvDialog
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvListRow
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.tv.TvTextField
import com.vayunmathur.library.tv.tvContentPadding
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.util.YouPipeViewModel

/**
 * TV add-to-playlist: staged membership as a focusable list with a confirm dialog.
 *
 * The phone stages membership in an `AddToListDialog` and applies on OK. Same staging
 * here — each playlist row toggles membership on CENTER — and OK/Cancel live in the
 * header. The new-playlist field is a [TvTextField] (IME only on CENTER). BACK pops
 * without applying.
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
        TvScreenHeader(
            title = stringResource(R.string.add_to_playlist),
            actions = {
                TvButton(
                    text = stringResource(R.string.action_close),
                    onClick = { backStack.pop() },
                )
            },
        )
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
    TvSettingsBody {
        Column(Modifier.fillMaxSize()) {
            TvScreenHeader(
                title = stringResource(R.string.add_to_playlist),
                actions = {
                    TvButton(
                        text = stringResource(UiR.string.ok),
                        onClick = ::confirm,
                        primary = true,
                    )
                    TvButton(
                        text = stringResource(UiR.string.cancel),
                        onClick = { backStack.pop() },
                    )
                },
            )
            LazyColumn(
                state = rememberLazyListState(),
                modifier = Modifier.fillMaxSize(),
                contentPadding = tvContentPadding(top = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
            items(playlists, key = { it.id }) { playlist ->
                val checked = playlist.id in staged
                TvListRow(
                    headline = if (playlist.mandatory) watchLaterLabel else playlist.name,
                    trailing = if (checked) {
                        { IconCheck() }
                    } else {
                        null
                    },
                    selected = checked,
                    onClick = {
                        staged = if (checked) staged - playlist.id else staged + playlist.id
                    },
                )
            }
            item(key = "new-playlist") {
                var nameOpen by remember { mutableStateOf(false) }
                if (!nameOpen) {
                    TvListRow(
                        headline = stringResource(R.string.new_playlist),
                        onClick = { nameOpen = true },
                    )
                } else {
                    val canCreate = newName.isNotBlank() &&
                        allPlaylists.none { it.name == newName.trim() }
                    TvTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = stringResource(R.string.label_playlist_name),
                        onSubmit = {
                            if (canCreate) {
                                ypvm.playlistOps.createPlaylistAndAddVideo(newName.trim(), video)
                                backStack.pop()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TvButton(
                        text = stringResource(R.string.new_playlist),
                        onClick = {
                            ypvm.playlistOps.createPlaylistAndAddVideo(newName.trim(), video)
                            backStack.pop()
                        },
                        enabled = canCreate,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            }
        }
    }
}
