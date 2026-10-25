package com.vayunmathur.appstore.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.util.SourceFilter
import com.vayunmathur.library.tv.TvChip
import com.vayunmathur.library.tv.TvDimens
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvLoading
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.tv.TvTextField
import com.vayunmathur.library.tv.tvContentPadding
import com.vayunmathur.library.tv.tvContentStart
import com.vayunmathur.library.ui.IconPackage
import com.vayunmathur.library.ui.IconSearch

/**
 * The TV search hub: a D-pad text field, source-filter chips, and the local-then-remote
 * merged results as a grid of row cards.
 *
 * The header, field and chips sit above the results grid in a Column — focus moves UP
 * from the first result row straight into them — and the one [LazyVerticalGrid] owns
 * results scrolling with [tvContentPadding]. The keyboard opens only on an explicit
 * CENTER press on the field ([TvTextField]); selecting the Search rail destination does
 * not start editing. Results arrive in two waves — the on-disk catalogues answer first,
 * the network merges in — so a searching line sits over content rather than over a
 * spinner. A Play refusal (e.g. 429) is said above the results, never a silent gap.
 */
@Composable
fun TvStoreSearch(
    viewModel: TvStoreViewModel,
    onAppClick: (UnifiedApp) -> Unit,
    /** True when the user navigated here explicitly: start with the field editing. */
    startEditing: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.search.collectAsState()
    TvStoreSearchScreen(
        state = state,
        startEditing = startEditing,
        onQueryChange = viewModel::setSearch,
        onFilterChange = viewModel::setSearchFilter,
        onAppClick = onAppClick,
        modifier = modifier,
    )
}

@Composable
internal fun TvStoreSearchScreen(
    state: TvSearchUiState,
    modifier: Modifier = Modifier,
    startEditing: Boolean = false,
    onQueryChange: (String) -> Unit = {},
    onFilterChange: (SourceFilter) -> Unit = {},
    onAppClick: (UnifiedApp) -> Unit = {},
) {
    val fieldFocus = remember(state.query) { FocusRequester() }
    // Play's half refused (429): say so above the results rather than silently
    // showing a list that is missing half its sources.
    val playError = state.playError.takeIf { it.isNotBlank() }
    Column(modifier.fillMaxSize()) {
        TvScreenHeader(
            title = stringResource(R.string.nav_search),
            subtitle = if (state.isSearching && state.hasSearched) {
                stringResource(R.string.tv_searching)
            } else {
                null
            },
        )
        TvTextField(
            value = state.query,
            onValueChange = onQueryChange,
            placeholder = stringResource(R.string.search_placeholder),
            leading = { IconSearch() },
            imeAction = ImeAction.Search,
            focusRequester = fieldFocus,
            startEditing = startEditing && state.query.isBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = tvContentStart(), end = TvDimens.SafeHorizontal, bottom = 8.dp),
        )
        TvInitialFocus(fieldFocus)
        TvStoreSourceFilters(
            filter = state.filter,
            onFilterChange = onFilterChange,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        if (playError != null) {
            TvStoreNoticeLine(text = playError, isError = true)
        }
        when {
            state.query.isBlank() -> TvEmptyState(
                title = stringResource(R.string.search_prompt_title),
                body = stringResource(R.string.tv_search_prompt_tv),
                icon = { IconSearch() },
            )
            state.results.isEmpty() && (state.isSearching || !state.hasSearched) ->
                TvLoading(label = stringResource(R.string.sync_in_progress))
            state.results.isEmpty() -> TvEmptyState(
                title = stringResource(R.string.search_no_results_title, state.query),
                body = stringResource(R.string.search_no_results_message),
                icon = { IconPackage() },
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = tvContentPadding(bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(state.results, key = { it.packageName }) { app ->
                    val summary = app.summary.ifBlank { app.author.orEmpty() }
                    TvStoreAppRow(
                        app = app,
                        onClick = { onAppClick(app) },
                        supporting = summary,
                        badge = when {
                            app.packageName in state.installedPackages -> stringResource(R.string.installed)
                            else -> null
                        },
                    )
                }
            }
        }
    }
}

/** Source-filter chips as a focusable D-pad row; LEFT/RIGHT moves, DOWN drops to results. */
@Composable
private fun TvStoreSourceFilters(
    filter: SourceFilter,
    onFilterChange: (SourceFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = tvContentStart(), end = TvDimens.SafeHorizontal),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (entry in SourceFilter.entries) {
            TvChip(
                label = stringResource(entry.tvLabelRes()),
                selected = filter == entry,
                onClick = { onFilterChange(entry) },
            )
        }
    }
}

internal fun SourceFilter.tvLabelRes(): Int = when (this) {
    SourceFilter.ALL -> R.string.filter_all
    SourceFilter.MODERN_APPS -> R.string.source_chip_modern_apps
    SourceFilter.PROPRIETARY -> R.string.source_chip_proprietary
    SourceFilter.FDROID -> R.string.source_chip_fdroid
    SourceFilter.PLAYSTORE -> R.string.source_chip_play
    SourceFilter.ACCRESCENT -> R.string.source_chip_accrescent
}
