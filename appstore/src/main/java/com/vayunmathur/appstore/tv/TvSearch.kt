package com.vayunmathur.appstore.tv

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.appstore.util.SourceFilter
import com.vayunmathur.library.ui.IconDownload
import kotlinx.coroutines.delay

/**
 * The TV search hub: query field, source-filter chips, and the local-then-Play merged results.
 *
 * Follows youpipe's `TvSearchPage` D-pad pattern: an entry row holds initial focus and a
 * CENTER press moves focus into the field (opening the TV IME); the IME's search action
 * submits. Results arrive in two waves — the on-disk catalogues answer first, Play's reply
 * merges in a moment later — so the progress line sits above a list that already has
 * content. A Play refusal (e.g. 429) is said above the list, never a silent gap.
 */
@Composable
fun TvStoreSearch(
    viewModel: TvStoreViewModel,
    modifier: Modifier = Modifier,
    onAppClick: (UnifiedApp) -> Unit = {},
) {
    val state by viewModel.search.collectAsState()
    TvSearchScreen(
        state = state,
        onQueryChange = viewModel::setSearch,
        onFilterChange = viewModel::setSearchFilter,
        onAppClick = onAppClick,
        modifier = modifier,
    )
}

/**
 * Stateless search hub, so it can be rendered in a preview.
 *
 * // RAW SCAFFOLD EXCEPTION: androidx.tv has no AppScaffold equivalent; the hub is a
 * titled column with a D-pad list, not a scaffolded phone screen.
 */
@Composable
internal fun TvSearchScreen(
    state: TvSearchUiState,
    modifier: Modifier = Modifier,
    onQueryChange: (String) -> Unit = {},
    onFilterChange: (SourceFilter) -> Unit = {},
    onAppClick: (UnifiedApp) -> Unit = {},
) {
    var fieldText by remember(state.query) { mutableStateOf(state.query) }
    var fieldOpen by remember { mutableStateOf(false) }
    val entryFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    fun submit() {
        onQueryChange(fieldText)
        fieldOpen = false
        focusManager.clearFocus()
    }

    TvSearchInitialFocus(entryFocus)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.nav_search),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // The entry row: focused by default, CENTER opens the field, never the IME directly.
        Surface(
            onClick = { fieldOpen = true },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(entryFocus),
        ) {
            Text(
                text = fieldText.ifBlank { stringResource(R.string.search_placeholder) },
                style = MaterialTheme.typography.titleMedium,
                color = if (fieldText.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.padding(16.dp),
            )
        }

        if (fieldOpen) {
            // The actual text field: only composed after the explicit open, so the IME can
            // only ever appear as the result of a CENTER press. IME search action submits.
            BasicTextField(
                value = fieldText,
                onValueChange = {
                    fieldText = it
                    onQueryChange(it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fieldFocus),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
            )
            LaunchedEffect(fieldFocus) {
                fieldFocus.requestFocus()
            }
        }

        TvSourceFilterRow(filter = state.filter, onFilterChange = onFilterChange)

        if (state.isSearching && state.results.isNotEmpty()) {
            Text(
                text = stringResource(R.string.updates_checking),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Play's half refused (429): say so above the results rather than silently
        // showing a list that is missing half its sources.
        val playError = state.playError.takeIf { it.isNotBlank() }
        if (playError != null) {
            Text(
                text = playError,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        when {
            state.query.isBlank() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.search_prompt_message),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            state.results.isEmpty() && (state.isSearching || !state.hasSearched) -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.sync_in_progress),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.results.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.search_no_results_title, state.query),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(state.results, key = { it.packageName }) { app ->
                    TvSearchRow(
                        app = app,
                        isInstalled = app.packageName in state.installedPackages,
                        stage = state.stages[app.packageName],
                        onClick = { onAppClick(app) },
                    )
                }
            }
        }
    }
}

/** Source-filter chips as a focusable D-pad row. */
@Composable
private fun TvSourceFilterRow(
    filter: SourceFilter,
    onFilterChange: (SourceFilter) -> Unit,
) {
    // A TabRow is the tv-material D-pad answer to a chip row: LEFT/RIGHT moves between
    // filters, DOWN drops into the results. No swipe, no pointer.
    TabRow(selectedTabIndex = SourceFilter.entries.indexOf(filter)) {
        for (entry in SourceFilter.entries) {
            Tab(
                selected = filter == entry,
                onFocus = { onFilterChange(entry) },
                onClick = { onFilterChange(entry) },
            ) {
                Text(stringResource(entry.tvLabelRes()))
            }
        }
    }
}

private fun SourceFilter.tvLabelRes(): Int = when (this) {
    SourceFilter.ALL -> R.string.filter_all
    SourceFilter.MODERN_APPS -> R.string.source_chip_modern_apps
    SourceFilter.PROPRIETARY -> R.string.source_chip_proprietary
    SourceFilter.FDROID -> R.string.source_chip_fdroid
    SourceFilter.PLAYSTORE -> R.string.source_chip_play
    SourceFilter.ACCRESCENT -> R.string.source_chip_accrescent
}

/** One search hit: name, summary, source, and install/update status. */
@Composable
private fun TvSearchRow(
    app: UnifiedApp,
    isInstalled: Boolean,
    stage: InstallStage?,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = app.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (app.summary.isNotBlank()) {
                Text(
                    text = app.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val status = when {
                stage != null -> stage.tvSearchLabel()
                isInstalled -> stringResource(R.string.installed)
                else -> null
            }
            if (status != null) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (stage is InstallStage.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun InstallStage.tvSearchLabel(): String = when (this) {
    InstallStage.Preparing -> stringResource(R.string.stage_preparing)
    is InstallStage.Downloading -> stringResource(R.string.stage_downloading, (fraction * 100).toInt())
    InstallStage.Verifying -> stringResource(R.string.stage_verifying)
    InstallStage.Installing -> stringResource(R.string.stage_installing)
    is InstallStage.Failed -> stringResource(R.string.stage_failed, reason)
}

/** Request focus once composed, so the D-pad has somewhere to start on this hub. */
@Composable
private fun TvSearchInitialFocus(requester: FocusRequester) {
    LaunchedEffect(requester) {
        // One frame late: requesting during composition races the focus system on first
        // layout and silently drops, leaving the D-pad dead until the user presses something.
        delay(FRAME_DELAY_MS)
        runCatching { requester.requestFocus() }
    }
}

private const val FRAME_DELAY_MS = 16L
