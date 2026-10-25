package com.vayunmathur.uniflix.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvLoading
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.tv.TvTextField
import com.vayunmathur.library.tv.tvContentPadding
import com.vayunmathur.library.tv.tvContentStart
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.uniflix.R
import com.vayunmathur.uniflix.domain.MediaItem
import com.vayunmathur.uniflix.platform.SearchViewModel

/**
 * TV search: a D-pad text field (IME only on CENTER) above a poster grid.
 *
 * The same debounced [SearchViewModel] the phone search uses — the field never
 * autofocuses, so the keyboard only ever appears on an explicit press.
 */
@Composable
fun TvSearchTab(onOpenDetail: (MediaItem) -> Unit) {
    val vm: SearchViewModel = viewModel()
    val state = vm.state
    val fieldFocus = remember { FocusRequester() }
    // Initial focus lands on the field so the D-pad has somewhere to start;
    // the keyboard still waits for CENTER (see TvTextField). Results never steal
    // focus: DOWN from the field reaches the grid through normal D-pad order.
    TvInitialFocus(fieldFocus, key = Unit)

    Column(Modifier.fillMaxSize()) {
        TvScreenHeader(title = stringResource(UiR.string.search))
        TvTextField(
            value = state.query,
            onValueChange = vm::onQueryChange,
            placeholder = stringResource(R.string.search_hint),
            modifier = Modifier.fillMaxWidth()
                .padding(start = tvContentStart(), end = 48.dp, bottom = 16.dp)
                .focusRequester(fieldFocus),
            leading = { IconSearch() },
            imeAction = ImeAction.Search,
        )
        if (state.loading && state.results.isEmpty()) {
            TvLoading()
            return@Column
        }
        if (state.results.isEmpty()) {
            if (state.query.isNotBlank()) {
                TvEmptyState(title = stringResource(R.string.no_results))
            }
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            modifier = Modifier.fillMaxSize(),
            contentPadding = tvContentPadding(top = 0.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(state.results, key = ::mediaKey) { item ->
                MediaPosterCard(
                    item = item,
                    onOpen = { onOpenDetail(item) },
                    width = 200.dp,
                )
            }
        }
    }
}
