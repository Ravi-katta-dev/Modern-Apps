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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvDialog
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.tv.tvContentPadding
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.util.YouPipeViewModel

/**
 * TV history: a 4-column grid of [TvVideoCard]s with D-pad multiselect and a clear-all
 * action in the header.
 *
 * The phone uses long-press to enter selection and a FAB to delete. A TV has no long-press
 * convention and no FAB within reach — CENTER on a card opens it, long-press (where the
 * remote has it) OR the header "Select" toggle enters selection, and delete/clear are
 * focused buttons in the header. BACK clears the selection first.
 */
@Composable
fun TvHistoryPage(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
) {
    val history by ypvm.historyVideosByRecency.collectAsState()
    val historyById = remember(history) { history.associate { it.id to it.progress } }
    val videos = remember(history) { history.map { it.videoItem } }
    val rows = rememberTvVideoRows(videos, ypvm, historyById)

    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    var confirmClear by remember { mutableStateOf(false) }
    val inSelectionMode = selectedIds.isNotEmpty()
    BackHandler(enabled = inSelectionMode) { selectedIds = emptySet() }

    val firstFocus = remember { FocusRequester() }
    TvInitialFocus(firstFocus)
    Column(Modifier.fillMaxSize()) {
        TvScreenHeader(
            title = if (inSelectionMode) {
                stringResource(R.string.selected_count, selectedIds.size.toString())
            } else {
                stringResource(R.string.title_history)
            },
            actions = {
                if (inSelectionMode) {
                    TvButton(
                        text = stringResource(UiR.string.delete),
                        onClick = {
                            ypvm.deleteHistoryVideos(selectedIds.toList())
                            selectedIds = emptySet()
                        },
                        icon = { IconDelete() },
                    )
                    TvButton(
                        text = stringResource(UiR.string.cancel),
                        onClick = { selectedIds = emptySet() },
                    )
                } else if (rows.isNotEmpty()) {
                    TvButton(
                        text = stringResource(R.string.clear_history),
                        onClick = { confirmClear = true },
                        icon = { IconDelete() },
                    )
                }
            },
        )
        if (rows.isEmpty()) {
            TvEmptyState(title = stringResource(R.string.empty_recommendations))
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(TV_GRID_COLUMNS),
            state = rememberLazyGridState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = tvContentPadding(top = 14.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(28.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(20.dp),
        ) {
            items(rows.size, key = { rows[it].row.videoID }) { index ->
                val item = rows[index]
                val isSelected = item.row.videoID in selectedIds
                TvSelectableCard(
                    item = item,
                    selected = isSelected,
                    inSelectionMode = inSelectionMode,
                    onOpen = { backStack.add(TvRoute.Video(item.row.videoID)) },
                    onToggleSelect = {
                        selectedIds = if (isSelected) {
                            selectedIds - item.row.videoID
                        } else {
                            selectedIds + item.row.videoID
                        }
                    },
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
    }
    if (confirmClear) {
        TvDialog(
            title = stringResource(R.string.clear_history),
            onDismissRequest = { confirmClear = false },
            body = stringResource(R.string.are_you_sure_you_want_to_clear_all_watch),
            buttons = {
                TvButton(
                    text = stringResource(UiR.string.clear),
                    onClick = {
                        ypvm.clearHistory()
                        confirmClear = false
                    },
                    primary = true,
                )
                TvButton(
                    text = stringResource(UiR.string.cancel),
                    onClick = { confirmClear = false },
                )
            },
        )
    }
}
