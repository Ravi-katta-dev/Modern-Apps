package com.vayunmathur.appstore.tv

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.library.ui.IconDownload

/**
 * The TV updates hub: pending updates with old → new version deltas, plus update-all.
 *
 * Mirrors the phone's `UpdatesPage`: opening the tab is the request, so the first visit
 * kicks off a check; afterwards the list is the merged catalogue + Play + Accrescent
 * streams. No pull-to-refresh — a TV has no touch — so the check button is the refresh
 * path. Failures of an update-all run surface once via AppMessages (see
 * [TvStoreViewModel.updateAll]), not per row.
 */
@Composable
fun TvStoreUpdates(
    viewModel: TvStoreViewModel,
    modifier: Modifier = Modifier,
    onAppClick: (UnifiedApp) -> Unit = {},
) {
    val state by viewModel.updatesUi.collectAsState()
    // Opening the tab is the request: the phone made the user tap "Sync F-Droid" then
    // "Check Play" by hand, which nobody remembers to do.
    LaunchedEffect(Unit) {
        if (state.lastCheckedAt == 0L) viewModel.checkForUpdates()
    }
    TvUpdatesScreen(
        state = state,
        onCheckForUpdates = viewModel::checkForUpdates,
        onUpdateAll = viewModel::updateAll,
        onInstall = viewModel::install,
        onAppClick = onAppClick,
        modifier = modifier,
    )
}

/**
 * Stateless updates hub, so it can be rendered in a preview.
 *
 * // RAW SCAFFOLD EXCEPTION: androidx.tv has no AppScaffold equivalent; the hub is a
 * titled column with a D-pad list, not a scaffolded phone screen.
 */
@Composable
internal fun TvUpdatesScreen(
    state: TvUpdatesUiState,
    modifier: Modifier = Modifier,
    onCheckForUpdates: () -> Unit = {},
    onUpdateAll: () -> Unit = {},
    onInstall: (UnifiedApp) -> Unit = {},
    onAppClick: (UnifiedApp) -> Unit = {},
) {
    val anyInstalling = state.stages.values.any { it !is InstallStage.Failed }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.nav_updates),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Button(
            onClick = onCheckForUpdates,
            enabled = !state.isChecking,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (state.isChecking) stringResource(R.string.updates_checking)
                else stringResource(R.string.action_check_again)
            )
        }
        if (state.updates.isNotEmpty()) {
            Button(
                onClick = onUpdateAll,
                enabled = !anyInstalling,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // IconXxx from library/ui — never Icon()/painterResource() in app modules.
                IconDownload()
                Text(stringResource(R.string.update_all, state.updates.size))
            }
        }

        val status = state.statusMessage.takeIf { it.isNotBlank() }
            ?: state.lastCheckedAt.takeIf { it > 0 }?.let {
                stringResource(
                    R.string.updates_last_checked,
                    DateUtils.getRelativeTimeSpanString(
                        it,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                    ),
                )
            }
        if (status != null) {
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.updates.isEmpty() && !state.isChecking) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.all_apps_up_to_date),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Text(
                text = pluralStringResource(
                    R.plurals.updates_count,
                    state.updates.size,
                    state.updates.size,
                ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(state.updates, key = { it.packageName }) { app ->
                    val stage = state.stages[app.packageName]
                    val installed = state.installedInfos[app.packageName]
                    val versionLabel = installed?.let { info ->
                        val old = info.versionName?.let { "$it (${info.versionCode})" }
                            ?: info.versionCode.toString()
                        val new = app.versionName?.let { "$it (${app.versionCode})" }
                            ?: app.versionCode.toString()
                        stringResource(R.string.version_update, old, new)
                    }
                    TvUpdateRow(
                        app = app,
                        versionLabel = versionLabel,
                        stage = stage,
                        onUpdate = { onInstall(app) },
                        onClick = { onAppClick(app) },
                    )
                }
            }
        }
    }
}

/** One pending update: name, old → new delta, and an update action. */
@Composable
private fun TvUpdateRow(
    app: UnifiedApp,
    versionLabel: String?,
    stage: InstallStage?,
    onUpdate: () -> Unit,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = app.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (versionLabel != null) {
                Text(
                    text = versionLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (stage is InstallStage.Failed) {
                Text(
                    text = stringResource(R.string.stage_failed, stage.reason),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(
                onClick = onUpdate,
                enabled = stage == null || stage is InstallStage.Failed,
            ) {
                Text(
                    if (stage != null && stage !is InstallStage.Failed) stage.tvUpdateStageLabel()
                    else stringResource(R.string.action_update)
                )
            }
        }
    }
}

@Composable
private fun InstallStage.tvUpdateStageLabel(): String = when (this) {
    InstallStage.Preparing -> stringResource(R.string.stage_preparing)
    is InstallStage.Downloading -> stringResource(R.string.stage_downloading, (fraction * 100).toInt())
    InstallStage.Verifying -> stringResource(R.string.stage_verifying)
    InstallStage.Installing -> stringResource(R.string.stage_installing)
    is InstallStage.Failed -> stringResource(R.string.stage_failed, reason)
}
