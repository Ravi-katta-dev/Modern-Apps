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
import androidx.compose.runtime.getValue
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
import androidx.tv.material3.Button
import androidx.tv.material3.Checkbox
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.ui.videoRowState
import com.vayunmathur.youpipe.util.YouPipeViewModel

/**
 * TV history: focusable grid with D-pad multiselect.
 *
 * The phone uses long-press to enter selection and a FAB to delete. A TV has no long-press
 * convention and no FAB within reach — so selection is a checkbox on each focused card
 * (RIGHT onto the box, CENTER toggles), and delete is a focused action row that appears only
 * while the selection is non-empty. BACK clears the selection first, matching the phone's
 * `BackHandler` and the overlay-dismiss-first rule.
 */
@Composable
fun TvHistoryPage(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
) {
    val history by ypvm.historyVideosByRecency.collectAsState()
    val deArrowEnabled by ypvm.deArrowEnabled.collectAsState()
    val deArrowCache by ypvm.deArrowCache.collectAsState()
    val context = LocalContext.current
    val firstFocus = remember { FocusRequester() }

    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    val inSelectionMode = selectedIds.isNotEmpty()
    BackHandler(enabled = inSelectionMode) { selectedIds = emptySet() }

    val rows = remember(history, deArrowEnabled, deArrowCache) {
        history.map { item ->
            val video = item.videoItem
            val deArrow = if (deArrowEnabled) deArrowCache[video.videoID] else null
            item.id to videoRowState(
                context = context,
                videoInfo = video,
                showAuthor = true,
                percentWatched = if (video.duration > 0) {
                    (item.progress.toDouble() / video.duration).toFloat()
                } else {
                    0f
                },
                deArrowTitle = deArrow?.title,
                deArrowThumbnailURL = deArrow?.thumbnailUrl,
            )
        }
    }

    TvInitialFocus(firstFocus)
    val gridState = rememberLazyGridState()
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        Text(text = stringResource(R.string.title_history), style = MaterialTheme.typography.headlineSmall)
        if (inSelectionMode) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(vertical = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.selected_count, selectedIds.size),
                    style = MaterialTheme.typography.titleMedium,
                )
                Button(
                    onClick = {
                        ypvm.deleteHistoryVideos(selectedIds.toList())
                        selectedIds = emptySet()
                    },
                ) {
                    IconDelete()
                }
                Button(onClick = { selectedIds = emptySet() }) {
                    Text(stringResource(R.string.action_close))
                }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            modifier = Modifier.fillMaxSize().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(rows, key = { it.first }) { (id, row) ->
                val isSelected = id in selectedIds
                Column {
                    TvVideoCard(
                        row = row,
                        onOpen = {
                            if (inSelectionMode) {
                                selectedIds = if (isSelected) selectedIds - id else selectedIds + id
                            } else {
                                backStack.add(TvRoute.Video(row.videoID))
                            }
                        },
                        modifier = if (rows.indexOfFirst { it.first == id } == 0) {
                            Modifier.focusRequester(firstFocus)
                        } else {
                            Modifier
                        },
                        trailingContent = {
                            // The selection checkbox: a second focus target on the card, so a
                            // focused card offers open (CENTER) and select (RIGHT then CENTER).
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 8.dp),
                            ) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = {
                                        selectedIds = if (isSelected) selectedIds - id else selectedIds + id
                                    },
                                )
                                Text(
                                    text = stringResource(R.string.selected_count, selectedIds.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}
