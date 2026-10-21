package com.vayunmathur.uniflix.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.uniflix.platform.AddonsViewModel
import com.vayunmathur.uniflix.ui.dialogs.AddAddonDialog

/**
 * Addon manager: installed URL addons with enable toggle, default star,
 * priority ordering, error badges, and add-by-URL. State lives here; the
 * stateless [AddonsContent] body renders previews without a ViewModel.
 */
@Composable
fun AddonsScreen(onBack: () -> Unit, onOpenDetail: (addonDbId: Long) -> Unit) {
    val vm: AddonsViewModel = viewModel()
    val rows by vm.addons.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    AddonsContent(
        rows = rows,
        adding = vm.adding,
        refreshing = vm.refreshing,
        onBack = onBack,
        onAddClick = { showAddDialog = true },
        onToggle = { row -> vm.setEnabled(row, !row.entity.enabled) },
        onSetDefault = { row -> vm.setDefault(row) },
        onClearDefault = { vm.clearDefault() },
        onMoveUp = { row -> vm.moveUp(rows, row) },
        onMoveDown = { row -> vm.moveDown(rows, row) },
        onRemove = { row -> vm.remove(row) },
        onRefresh = { row -> vm.refreshManifest(row) },
        onOpenDetail = onOpenDetail,
    )

    if (showAddDialog) {
        AddAddonDialog(
            adding = vm.adding,
            onDismiss = { showAddDialog = false },
            onConfirm = { url ->
                vm.addByUrl(url)
                showAddDialog = false
            },
        )
    }
}
