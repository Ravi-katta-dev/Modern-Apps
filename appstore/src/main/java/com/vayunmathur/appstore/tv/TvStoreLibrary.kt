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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.util.SourceFilter
import com.vayunmathur.library.tv.TvChip
import com.vayunmathur.library.tv.TvDimens
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.tv.tvContentPadding
import com.vayunmathur.library.tv.tvContentStart
import com.vayunmathur.library.ui.IconPackage

/**
 * Every installed app the store can attribute to a source, grouped by source chip.
 *
 * The header and chips sit above the grid in a Column — focus moves UP from the first
 * row straight into them — and the one [LazyVerticalGrid] owns scrolling with
 * [tvContentPadding]. Uninstall lives only on an app's own page: a delete action next
 * to every tile in a long grid is too easy to land on with a D-pad. Appearing on this
 * destination refreshes the device's installed set, so an install that landed while
 * browsing shows up.
 */
@Composable
fun TvStoreLibrary(
    viewModel: TvStoreViewModel,
    onAppClick: (UnifiedApp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.library.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshInstalled() }
    TvStoreLibraryScreen(
        state = state,
        onFilterChange = viewModel::setLibraryFilter,
        onAppClick = onAppClick,
        modifier = modifier,
    )
}

@Composable
internal fun TvStoreLibraryScreen(
    state: TvLibraryUiState,
    modifier: Modifier = Modifier,
    onFilterChange: (SourceFilter) -> Unit = {},
    onAppClick: (UnifiedApp) -> Unit = {},
) {
    Column(modifier.fillMaxSize()) {
        TvScreenHeader(title = stringResource(R.string.nav_library))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = tvContentStart(),
                    end = TvDimens.SafeHorizontal,
                    bottom = 12.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (entry in SourceFilter.entries) {
                val count = state.counts[entry] ?: 0
                TvChip(
                    label = stringResource(
                        R.string.filter_with_count,
                        stringResource(entry.tvLabelRes()),
                        count,
                    ),
                    selected = state.filter == entry,
                    onClick = { onFilterChange(entry) },
                )
            }
        }
        if (state.apps.isEmpty()) {
            TvEmptyState(
                title = stringResource(R.string.library_empty_title),
                body = stringResource(R.string.library_empty_message),
                icon = { IconPackage() },
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = tvContentPadding(bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(state.apps, key = { it.packageName }) { app ->
                    TvStoreAppRow(
                        app = app,
                        onClick = { onAppClick(app) },
                        supporting = app.versionName,
                        badge = stringResource(R.string.installed),
                    )
                }
            }
        }
    }
}
