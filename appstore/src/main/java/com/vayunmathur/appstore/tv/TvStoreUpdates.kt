package com.vayunmathur.appstore.tv

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvProgressStrip
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.tv.tvContentStart
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconRefresh

/**
 * The TV updates hub: pending updates with old → new version deltas, per-app update
 * buttons with live progress, and an "Update all" primary action in the header.
 *
 * Mirrors the phone's UpdatesPage: opening the destination is the request, so the first
 * visit kicks off a check. No pull-to-refresh — a TV has no touch — so a check button
 * sits next to Update all. Failures of an update-all run surface once via AppMessages
 * (see [TvStoreViewModel.updateAll]), not per row.
 */
@Composable
fun TvStoreUpdates(
    viewModel: TvStoreViewModel,
    onAppClick: (UnifiedApp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.updatesUi.collectAsState()
    // Opening the tab is the request: the phone made the user tap "Sync F-Droid" then
    // "Check Play" by hand, which nobody remembers to do.
    LaunchedEffect(Unit) {
        if (state.lastCheckedAt == 0L) viewModel.checkForUpdates()
    }
    TvStoreUpdatesScreen(
        state = state,
        onCheckForUpdates = viewModel::checkForUpdates,
        onUpdateAll = viewModel::updateAll,
        onInstall = viewModel::install,
        onAppClick = onAppClick,
        modifier = modifier,
    )
}

@Composable
internal fun TvStoreUpdatesScreen(
    state: TvUpdatesUiState,
    modifier: Modifier = Modifier,
    onCheckForUpdates: () -> Unit = {},
    onUpdateAll: () -> Unit = {},
    onInstall: (UnifiedApp) -> Unit = {},
    onAppClick: (UnifiedApp) -> Unit = {},
) {
    val primaryFocus = remember { FocusRequester() }
    val anyInstalling = state.stages.values.any { it !is InstallStage.Failed }
    // @Composable string resolution hoisted: LazyColumn trailing lambdas are not
    // @Composable scopes for non-local calls — plain values are used below.
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
    val countLabel = pluralStringResource(R.plurals.updates_count, state.updates.size, state.updates.size)
    // Unkeyed: runs once per visit. Keying on the count would yank focus back to
    // "Update all" every time a check completes or an update installs and drops off.
    TvInitialFocus(primaryFocus)
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item("header") {
            TvScreenHeader(
                title = stringResource(R.string.nav_updates),
                subtitle = status,
                actions = {
                    if (state.updates.isNotEmpty()) {
                        TvButton(
                            text = stringResource(R.string.update_all, state.updates.size),
                            onClick = onUpdateAll,
                            primary = true,
                            enabled = !anyInstalling,
                            icon = { IconDownload() },
                            modifier = Modifier.focusRequester(primaryFocus),
                        )
                    }
                    TvButton(
                        text = if (state.isChecking) {
                            stringResource(R.string.updates_checking)
                        } else {
                            stringResource(R.string.action_check_again)
                        },
                        onClick = onCheckForUpdates,
                        enabled = !state.isChecking,
                        icon = { IconRefresh() },
                    )
                },
            )
        }
        if (state.updates.isEmpty() && !state.isChecking) {
            item("empty") {
                TvEmptyState(
                    title = stringResource(R.string.all_apps_up_to_date),
                    body = stringResource(R.string.updates_empty_message),
                    icon = { IconCheck() },
                )
            }
        } else {
            item("count") {
                Text(
                    text = if (state.isChecking && state.updates.isEmpty()) {
                        stringResource(R.string.updates_checking)
                    } else {
                        countLabel
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(
                        start = tvContentStart(),
                        end = 48.dp,
                        bottom = 8.dp,
                    ),
                )
            }
            items(state.updates, key = { it.packageName }) { app ->
                val installed = state.installedInfos[app.packageName]
                val old = installed?.let { info ->
                    info.versionName?.let { "$it (${info.versionCode})" }
                        ?: info.versionCode.toString()
                }
                val new = app.versionName?.let { "$it (${app.versionCode})" }
                    ?: app.versionCode.toString()
                val versionLabel = old?.let { stringResource(R.string.version_update, it, new) }
                TvStoreUpdateRow(
                    app = app,
                    versionLabel = versionLabel,
                    stage = state.stages[app.packageName],
                    onUpdate = { onInstall(app) },
                    onClick = { onAppClick(app) },
                    modifier = Modifier.padding(start = tvContentStart(), end = 48.dp, bottom = 12.dp),
                )
            }
        }
    }
}

/** One pending update: icon, name, old → new delta, progress, and an update action. */
@Composable
private fun TvStoreUpdateRow(
    app: UnifiedApp,
    versionLabel: String?,
    stage: InstallStage?,
    onUpdate: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = stage != null && stage !is InstallStage.Failed
    Column(modifier.fillMaxWidth()) {
        TvStoreAppRow(
            app = app,
            onClick = onClick,
            supporting = versionLabel,
            badge = if (stage is InstallStage.Failed) {
                stringResource(R.string.stage_failed, stage.reason)
            } else {
                null
            },
            trailing = {
                TvButton(
                    text = if (busy) {
                        stage.tvStageLabel()
                    } else {
                        stringResource(R.string.action_update)
                    },
                    onClick = onUpdate,
                    primary = !busy,
                    enabled = !busy,
                )
            },
        )
        if (stage is InstallStage.Downloading) {
            TvProgressStrip(
                progress = stage.fraction.coerceIn(0f, 1f),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
