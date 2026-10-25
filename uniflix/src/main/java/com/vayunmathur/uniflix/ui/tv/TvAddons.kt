package com.vayunmathur.uniflix.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvDialog
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvIconButton
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvListRow
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.tv.TvSwitchRow
import com.vayunmathur.library.tv.TvTextField
import com.vayunmathur.library.tv.tvContentPadding
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconArrowDownward
import com.vayunmathur.library.ui.IconArrowUpward
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconRefresh
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.IconStarBorder
import com.vayunmathur.library.ui.IconWarning
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.uniflix.R
import com.vayunmathur.uniflix.platform.AddonRow
import com.vayunmathur.uniflix.platform.AddonsViewModel

/**
 * TV addon manager: one row per installed addon with enable toggle, default star,
 * reorder buttons and remove — plus add-by-URL via a [TvDialog] with a [TvTextField].
 *
 * Same [AddonsViewModel] ops as the phone manager. The .js file import (an activity
 * result on the phone) is out of scope for the D-pad: URL addons only here.
 */
@Composable
fun TvAddonsTab(onOpenDetail: (addonDbId: Long) -> Unit) {
    val vm: AddonsViewModel = viewModel()
    val rows by vm.addons.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    val firstFocus = remember { FocusRequester() }
    TvInitialFocus(firstFocus, key = rows.firstOrNull()?.entity?.dbId ?: "add")

    Column(Modifier.fillMaxSize()) {
        TvScreenHeader(
            title = stringResource(R.string.stream_addons),
            subtitle = stringResource(R.string.addons_explainer),
            actions = {
                TvIconButton(onClick = { vm.refreshAll() }, icon = { IconRefresh() })
                TvIconButton(onClick = { showAddDialog = true }, icon = { IconAdd() })
            },
        )
        if (rows.isEmpty()) {
            TvEmptyState(
                title = stringResource(R.string.no_addons_yet),
                action = {
                    TvButton(
                        text = stringResource(R.string.add_addon),
                        onClick = { showAddDialog = true },
                        modifier = Modifier.focusRequester(firstFocus),
                        icon = { IconAdd() },
                        primary = true,
                    )
                },
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = tvContentPadding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(rows, key = { "addon-${it.entity.dbId}" }) { row ->
                    TvAddonRow(
                        row = row,
                        isFirst = row == rows.firstOrNull(),
                        firstFocus = firstFocus,
                        onOpenDetail = { onOpenDetail(row.entity.dbId) },
                        onToggle = { vm.setEnabled(row, !row.entity.enabled) },
                        onSetDefault = {
                            if (row.isDefault) vm.clearDefault() else vm.setDefault(row)
                        },
                        onMoveUp = { vm.moveUp(rows, row) },
                        onMoveDown = { vm.moveDown(rows, row) },
                        onRemove = { vm.remove(row) },
                    )
                }
            }
        }
    }
    if (showAddDialog) {
        TvAddAddonDialog(
            adding = vm.adding,
            onDismiss = { showAddDialog = false },
            onConfirm = { url ->
                vm.addByUrl(url)
                showAddDialog = false
            },
        )
    }
}

/** One addon row: enable toggle, default star, reorder, refresh badge, remove. */
@Composable
internal fun TvAddonRow(
    row: AddonRow,
    isFirst: Boolean,
    firstFocus: FocusRequester,
    onOpenDetail: () -> Unit,
    onToggle: () -> Unit,
    onSetDefault: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val entity = row.entity
    val version = entity.version.ifBlank { null }
    val defaultLabel = stringResource(R.string.default_addon)
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TvListRow(
            headline = entity.name,
            onClick = onOpenDetail,
            modifier = if (isFirst) Modifier.focusRequester(firstFocus) else Modifier,
            supporting = listOfNotNull(
                version?.let { "v$it" },
                if (row.isDefault) defaultLabel else null,
                entity.lastError,
            ).joinToString("  •  ").ifBlank { null },
            leading = { if (row.isDefault) IconStar() else IconStarBorder() },
            trailing = { if (entity.lastError != null) IconWarning() },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvSwitchRow(
                headline = stringResource(R.string.enabled),
                checked = entity.enabled,
                onCheckedChange = { onToggle() },
                modifier = Modifier.weight(1f),
            )
            TvIconButton(onClick = onSetDefault, icon = { if (row.isDefault) IconStar() else IconStarBorder() })
            TvIconButton(onClick = onMoveUp, icon = { IconArrowUpward() })
            TvIconButton(onClick = onMoveDown, icon = { IconArrowDownward() })
            TvIconButton(onClick = onRemove, icon = { IconDelete() })
        }
    }
}

/** Add-by-URL dialog: a D-pad text field plus Add/Cancel. */
@Composable
internal fun TvAddAddonDialog(adding: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    TvDialog(
        title = stringResource(R.string.add_addon),
        onDismissRequest = onDismiss,
        body = stringResource(R.string.addon_url_example),
        content = {
            TvTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = stringResource(R.string.addon_url_hint),
                keyboardType = KeyboardType.Uri,
            )
        },
        buttons = {
            TvButton(
                text = stringResource(UiR.string.cancel),
                onClick = onDismiss,
                enabled = !adding,
            )
            TvButton(
                text = stringResource(UiR.string.add),
                onClick = { onConfirm(url) },
                enabled = !adding && url.isNotBlank(),
                primary = true,
            )
        },
    )
}
