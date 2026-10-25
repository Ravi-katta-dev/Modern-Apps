package com.vayunmathur.uniflix.ui.tv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.tv.TvSectionTitle
import com.vayunmathur.library.tv.TvShelf
import com.vayunmathur.uniflix.R
import com.vayunmathur.uniflix.data.ContinueWatchingEntry
import com.vayunmathur.uniflix.data.UserList
import com.vayunmathur.uniflix.data.UserListItem
import com.vayunmathur.uniflix.domain.MediaItem
import com.vayunmathur.uniflix.domain.MediaType
import com.vayunmathur.uniflix.platform.HomeViewModel
import com.vayunmathur.uniflix.platform.ListsViewModel

/**
 * TV My List: watchlist posters, the resume shelf, and user lists — shelves for
 * short collections, drunk on the same ViewModels the phone screens use.
 */
@Composable
fun TvMyListTab(
    onOpenDetail: (MediaItem) -> Unit,
    onResume: (ContinueWatchingEntry) -> Unit,
) {
    val homeVm: HomeViewModel = viewModel()
    val listsVm: ListsViewModel = viewModel()
    val watchlist by homeVm.watchlist.collectAsState(initial = emptyList())
    val entries by homeVm.continueWatching.collectAsState(initial = emptyList())
    val lists by listsVm.lists.collectAsState(initial = emptyList())

    val firstFocus = remember { FocusRequester() }
    val firstKey = when {
        entries.isNotEmpty() -> "cw:${entries.first().key}"
        watchlist.isNotEmpty() -> "wl:${watchlist.first().key}"
        lists.isNotEmpty() -> "list:${lists.first().id}"
        else -> null
    }
    TvInitialFocus(firstFocus, key = firstKey)

    Column(Modifier.fillMaxSize()) {
        TvScreenHeader(title = stringResource(R.string.my_list))
        if (entries.isEmpty() && watchlist.isEmpty() && lists.isEmpty()) {
            TvEmptyState(title = stringResource(R.string.no_results))
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (entries.isNotEmpty()) {
                item(key = "shelf-continue") {
                    TvShelf(title = stringResource(R.string.continue_watching)) {
                        items(entries, key = { "cw:${it.key}" }) { entry ->
                            ContinueEntryCard(
                                entry = entry,
                                onResume = { onResume(entry) },
                                modifier = focusIf("cw:${entry.key}", firstKey, firstFocus),
                            )
                        }
                    }
                }
            }
            if (watchlist.isNotEmpty()) {
                item(key = "shelf-watchlist") {
                    TvShelf(title = stringResource(R.string.watchlist)) {
                        items(watchlist, key = { "wl:${it.key}" }) { row ->
                            val item = row.toMediaItem()
                            MediaPosterCard(
                                item = item,
                                onOpen = { onOpenDetail(item) },
                                modifier = focusIf("wl:${row.key}", firstKey, firstFocus),
                            )
                        }
                    }
                }
            }
            items(lists, key = { "list:${it.id}" }) { list ->
                TvUserListShelf(
                    list = list,
                    firstKey = firstKey,
                    firstFocus = firstFocus,
                    onOpenDetail = onOpenDetail,
                )
            }
        }
    }
}

/** One user list as a shelf of posters. */
@Composable
internal fun TvUserListShelf(
    list: UserList,
    firstKey: String?,
    firstFocus: FocusRequester,
    onOpenDetail: (MediaItem) -> Unit,
) {
    val listsVm: ListsViewModel = viewModel()
    val items by listsVm.items(list.id).collectAsState(initial = emptyList())
    if (items.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        TvSectionTitle(list.name)
        TvShelf(title = null) {
            items(items, key = { "li:${it.id}" }) { row ->
                val item = row.toListItem()
                MediaPosterCard(
                    item = item,
                    onOpen = { onOpenDetail(item) },
                    modifier = focusIf("li:${row.id}", firstKey, firstFocus),
                )
            }
        }
    }
}

internal fun focusIf(key: String, firstKey: String?, firstFocus: FocusRequester): Modifier =
    if (key == firstKey) Modifier.focusRequester(firstFocus) else Modifier

private fun UserListItem.toListItem(): MediaItem = MediaItem(
    tmdbId = tmdbId,
    mediaType = if (mediaType == "tv") MediaType.TV else MediaType.MOVIE,
    title = title,
    posterPath = posterPath,
)
