package com.vayunmathur.uniflix.ui.tv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvGrid
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvLoading
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.tv.TvTextField
import com.vayunmathur.library.tv.tvContentStart
import com.vayunmathur.library.tv.tvFullWidth
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.uniflix.R
import com.vayunmathur.uniflix.domain.MediaItem
import com.vayunmathur.uniflix.platform.SearchViewModel

/**
 * TV search: a D-pad text field (IME only on CENTER) above a poster grid.
 * Same debounced [SearchViewModel] the phone search uses — the field never
 * autofocuses on results, so typing keeps focus in the field.
 */
@Composable
fun TvSearchTab(onOpenDetail: (MediaItem) -> Unit) {
    val vm: SearchViewModel = viewModel()
    val state = vm.state
    val fieldFocus = remember { FocusRequester() }
    // Initial focus lands on the field so the D-pad has somewhere to start;
    // the keyboard still waits for CENTER (see TvTextField). DOWN from the
    // field reaches the grid through normal D-pad order.
    TvInitialFocus(fieldFocus, key = Unit)

    Column(Modifier.fillMaxSize()) {
        TvScreenHeader(title = stringResource(UiR.string.search))
        TvTextField(
            value = state.query,
            onValueChange = vm::onQueryChange,
            placeholder = stringResource(R.string.search_hint),
            modifier = Modifier.fillMaxWidth()
                .padding(start = tvContentStart(), end = 48.dp, bottom = 8.dp),
            focusRequester = fieldFocus,
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
        TvGrid(columns = 7) {
            items(state.results, key = ::mediaKey) { item ->
                MediaPosterCard(
                    item = item,
                    onOpen = { onOpenDetail(item) },
                )
            }
            tvFullWidth(key = "end-anchor") {}
        }
    }
}
