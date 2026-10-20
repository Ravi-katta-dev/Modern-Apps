package com.vayunmathur.youpipe.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Checkbox
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.ui.videoRowState
import com.vayunmathur.youpipe.util.DownloadManager
import com.vayunmathur.youpipe.util.YouPipeViewModel

/**
 * TV downloads: active downloads with cancel + completed downloads with D-pad multiselect.
 *
 * Mirrors the phone `DownloadedVideosPage` binding (activeDownloads + downloadedVideosByRecency)
 * as one focusable list — no expanded-width grid switch, a TV reports one window size.
 * Selection is checkboxes (same pattern as history); BACK clears first.
 */
@Composable
fun TvDownloadsPage(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
) {
    val downloadedVideos by ypvm.downloadedVideos.collectAsState()
    val activeDownloads by DownloadManager.activeDownloads.collectAsState()
    val downloads by ypvm.downloadedVideosByRecency.collectAsState()
    val context = LocalContext.current
    val firstFocus = remember { FocusRequester() }

    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var selectedActiveIds by remember { mutableStateOf(setOf<Long>()) }
    val isSelectionMode = selectedIds.isNotEmpty() || selectedActiveIds.isNotEmpty()
    BackHandler(enabled = isSelectionMode) {
        selectedIds = emptySet()
        selectedActiveIds = emptySet()
    }

    TvInitialFocus(firstFocus)
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "downloads-title") {
            Text(
                text = if (isSelectionMode) {
                    stringResource(R.string.selected_count, selectedIds.size + selectedActiveIds.size)
                } else {
                    stringResource(R.string.title_downloads)
                },
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        if (isSelectionMode) {
            item(key = "downloads-actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
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
                    ) {
                        IconDelete()
                    }
                    Button(
                        onClick = {
                            selectedIds = emptySet()
                            selectedActiveIds = emptySet()
                        },
                    ) {
                        IconClose()
                    }
                }
            }
        }
        items(activeDownloads.toList(), key = { (videoID, _) -> "active-$videoID" }) { (videoID, status) ->
            val isSelected = videoID in selectedActiveIds
            Surface(
                onClick = {
                    if (isSelectionMode) {
                        selectedActiveIds = if (isSelected) selectedActiveIds - videoID else selectedActiveIds + videoID
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isSelectionMode) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = {
                                selectedActiveIds = if (isSelected) {
                                    selectedActiveIds - videoID
                                } else {
                                    selectedActiveIds + videoID
                                }
                            },
                        )
                    }
                    if (status.videoInfo.thumbnailURL.isNotEmpty()) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(status.videoInfo.thumbnailURL)
                                .memoryCacheKey("dl-thumb-$videoID")
                                .build(),
                            contentDescription = null,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(text = status.videoInfo.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "${(status.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    CircularProgressIndicator(
                        progress = { status.progress.toFloat() },
                        modifier = Modifier.size(24.dp),
                    )
                    if (!isSelectionMode) {
                        Button(onClick = { DownloadManager.cancelDownload(context, videoID) }) {
                            IconClose()
                        }
                    }
                }
            }
        }
        items(downloads, key = { it.id }) { downloadItem ->
            val isSelected = downloadItem.id in selectedIds
            TvVideoCard(
                row = videoRowState(
                    context = context,
                    videoInfo = downloadItem.videoItem,
                    showAuthor = true,
                ),
                onOpen = {
                    if (isSelectionMode) {
                        selectedIds = if (isSelected) selectedIds - downloadItem.id else selectedIds + downloadItem.id
                    } else {
                        backStack.add(TvRoute.Video(downloadItem.id))
                    }
                },
                modifier = if (activeDownloads.isEmpty() && downloads.indexOf(downloadItem) == 0) {
                    Modifier.focusRequester(firstFocus)
                } else {
                    Modifier
                },
                trailingContent = {
                    if (isSelectionMode) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = {
                                selectedIds = if (isSelected) {
                                    selectedIds - downloadItem.id
                                } else {
                                    selectedIds + downloadItem.id
                                }
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                },
            )
        }
    }
}
