package com.vayunmathur.uniflix.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvListRow
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.tv.TvSwitchRow
import com.vayunmathur.library.tv.TvTextField
import com.vayunmathur.library.tv.tvContentPadding
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconRefresh
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.uniflix.R
import com.vayunmathur.uniflix.platform.AddonsViewModel

/**
 * TV per-addon detail: manifest identity, health, enable/default toggles, test +
 * refresh actions, the opaque settings JSON and remove — same ops as the phone detail.
 */
@Composable
fun TvAddonDetailPage(backStack: NavBackStack<TvRoute>, addonDbId: Long) {
    val vm: AddonsViewModel = viewModel()
    val rows by vm.addons.collectAsState()
    val row = rows.firstOrNull { it.entity.dbId == addonDbId }

    val firstFocus = remember { FocusRequester() }
    TvInitialFocus(firstFocus, key = addonDbId)

    if (row == null) {
        Column(Modifier.fillMaxSize()) {
            TvScreenHeader(title = stringResource(R.string.stream_addons))
            TvEmptyState(
                title = stringResource(R.string.addon_not_found),
                action = { TvButton(text = stringResource(UiR.string.back), onClick = { backStack.pop() }) },
            )
        }
        return
    }
    val entity = row.entity
    var configDraft by remember(entity.configJson) { mutableStateOf(entity.configJson) }

    Column(Modifier.fillMaxSize()) {
        TvScreenHeader(title = entity.name)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = tvContentPadding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "identity") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TvListRow(
                        headline = stringResource(R.string.manifest_label),
                        onClick = {},
                        supporting = entity.manifestUrl,
                    )
                    if (entity.version.isNotBlank()) {
                        TvListRow(
                            headline = stringResource(R.string.version_label),
                            onClick = {},
                            supporting = entity.version,
                        )
                    }
                    entity.lastError?.let { error ->
                        TvListRow(
                            headline = stringResource(R.string.last_error_label),
                            onClick = {},
                            supporting = error,
                            leading = { IconDelete() },
                        )
                    }
                }
            }
            item(key = "toggles") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TvSwitchRow(
                        headline = stringResource(R.string.enabled),
                        checked = entity.enabled,
                        onCheckedChange = { vm.setEnabled(row, !entity.enabled) },
                    )
                    TvSwitchRow(
                        headline = stringResource(R.string.default_addon),
                        checked = row.isDefault,
                        onCheckedChange = {
                            if (row.isDefault) vm.clearDefault() else vm.setDefault(row)
                        },
                    )
                }
            }
            item(key = "actions") {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TvButton(
                            text = stringResource(R.string.test),
                            onClick = { vm.testAddon(row) },
                            primary = true,
                        )
                        TvButton(
                            text = stringResource(R.string.refresh),
                            onClick = { vm.refreshManifest(row) },
                            icon = { IconRefresh() },
                        )
                        TvButton(
                            text = stringResource(R.string.remove_addon),
                            onClick = { vm.remove(row); backStack.pop() },
                            icon = { IconDelete() },
                        )
                    }
                }
            }
            item(key = "config") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvListRow(
                        headline = stringResource(R.string.addon_settings),
                        onClick = {},
                    )
                    TvTextField(
                        value = configDraft,
                        onValueChange = { configDraft = it },
                        placeholder = "{}",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 0.dp),
                        imeAction = ImeAction.Done,
                    )
                    TvButton(
                        text = stringResource(R.string.save_settings),
                        onClick = { vm.updateConfig(entity.dbId, configDraft) },
                        enabled = configDraft != entity.configJson,
                        primary = true,
                    )
                }
            }
        }
    }
}
