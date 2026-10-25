package com.vayunmathur.youpipe.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvProgressStrip
import com.vayunmathur.library.tv.TvRowCard
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.tv.TvShelf
import com.vayunmathur.library.tv.tvContentPadding
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.util.DownloadManager
import com.vayunmathur.youpipe.util.YouPipeViewModel

/**
 * TV downloads: active downloads with cancel + completed downloads as a grid, with D-pad
 * multiselect. Mirrors the phone `DownloadedVideosPage` binding (activeDownloads +
 * downloadedVideosByRecency). Selection deletes; BACK clears first.
 */
@Composable
fun TvDownloadsPage(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
) {
    val downloadedVideos by ypvm.downloadedVideos.collectAsState()
    val activeDownloads by DownloadManager.activeDownloads.collectAsState()
    val downloads by ypvm.downloadedVideosByRecency.collectAsState()
    val historyById = remember(downloadedVideos) { emptyMap<Long, Long>() }
    val context = LocalContext.current
    val firstFocus = remember { FocusRequester() }

    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var selectedActiveIds by remember { mutableStateOf(setOf<Long>()) }
    val isSelectionMode = selectedIds.isNotEmpty() || selectedActiveIds.isNotEmpty()
    BackHandler(enabled = isSelectionMode) {
        selectedIds = emptySet()
        selectedActiveIds = emptySet()
    }

    val rows = rememberTvVideoRows(
        videos = remember(downloads) { downloads.map { it.videoItem } },
        ypvm = ypvm,
        historyProgressMsById = historyById,
    )

    TvInitialFocus(firstFocus)
    Column(Modifier.fillMaxSize()) {
        TvScreenHeader(
            title = if (isSelectionMode) {
                stringResource(R.string.selected_count, (selectedIds.size + selectedActiveIds.size).toString())
            } else {
                stringResource(R.string.title_downloads)
            },
            actions = {
                if (isSelectionMode) {
                    TvButton(
                        text = stringResource(UiR.string.delete),
                        onClick = {
                            selectedIds.forEach { id ->
                                downloadedVideos.find { it.id == id }?.let { ypvm.deleteDownloadedVideo(it) }
                            }
                            selectedActiveIds.forEach { id ->
                                DownloadManager.cancelDownload(context, id)
                            }
                            selectedIds = emptySet()
                            selectedActiveIds = emptySet()
                        },
                        icon = { IconDelete() },
                    )
                    TvButton(
                        text = stringResource(UiR.string.cancel),
                        onClick = {
                            selectedIds = emptySet()
                            selectedActiveIds = emptySet()
                        },
                        icon = { IconClose() },
                    )
                }
            },
        )
        val activeList = remember(activeDownloads) { activeDownloads.toList() }
        if (activeList.isNotEmpty()) {
            // Active downloads: progress rows. Cancel lives in the header delete flow —
            // long-press a row to select it, then delete cancels the download. No nested
            // buttons: a D-pad cannot land "inside" a row.
            TvShelf(title = stringResource(R.string.download)) {
                items(
                    activeList.size,
                    key = { index -> "active-${activeList[index].first}" },
                ) { index ->
                    val (videoID, status) = activeList[index]
                    val isSelected = videoID in selectedActiveIds
                    TvRowCard(
                        title = status.videoInfo.name,
                        subtitle = "${(status.progress * 100).toInt()}%" +
                            if (isSelected) "  •  ✓" else "",
                        leading = {
                            CircularProgressIndicator(
                                progress = { status.progress.toFloat() },
                                modifier = Modifier.size(40.dp),
                            )
                        },
                        width = 420.dp,
                        onClick = {
                            if (isSelectionMode) {
                                selectedActiveIds = if (isSelected) {
                                    selectedActiveIds - videoID
                                } else {
                                    selectedActiveIds + videoID
                                }
                            }
                        },
                        onLongClick = {
                            selectedActiveIds = if (isSelected) {
                                selectedActiveIds - videoID
                            } else {
                                selectedActiveIds + videoID
                            }
                        },
                    )
                }
            }
        }
        if (rows.isEmpty() && activeList.isEmpty()) {
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
            items(rows.size, key = { rows[it].row.videoID }) { index ->
                val item = rows[index]
                val isSelected = item.info.videoID in selectedIds
                TvSelectableCard(
                    item = item,
                    selected = isSelected,
                    inSelectionMode = isSelectionMode,
                    onOpen = { backStack.add(TvRoute.Video(item.row.videoID)) },
                    onToggleSelect = {
                        val id = item.info.videoID
                        selectedIds = if (isSelected) selectedIds - id else selectedIds + id
                    },
                    modifier = if (index == 0 && activeList.isEmpty()) {
                        Modifier.focusRequester(firstFocus)
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}
