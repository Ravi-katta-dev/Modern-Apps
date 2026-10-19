package com.vayunmathur.tvlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

// RAW SCAFFOLD EXCEPTION: TV launcher fullscreen grid, no app bar.

/**
 * The TV home screen: clock header, app banner grid, bottom glass row, edit overlays.
 *
 * Paints its own warm gradient like the tv-detox reference — a TV home never shows the
 * wallpaper behind it, so this screen is opaque by design.
 */
@Composable
fun TvHomeScreen(
    state: com.vayunmathur.tvlauncher.platform.TvHomeState,
    onOpenTile: (String) -> Unit,
    onOpenPlayStore: () -> Unit,
    onOpenInput: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNetworkSettings: () -> Unit,
    onOpenEditMenu: (String) -> Unit,
    onCloseEditMenu: () -> Unit,
    onStartMove: (String) -> Unit,
    onFinishMove: () -> Unit,
    onCancelMove: () -> Unit,
    onMoveKey: (String, Int) -> Unit,
    onRemoveTile: (String) -> Unit,
    onOpenAddMenu: (Int) -> Unit,
    onPickAddCandidate: (String) -> Unit,
    onUninstallOrInfo: (String) -> Unit,
    onRefreshHome: () -> Unit,
    onRequestHomeRole: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TvHomeBackground),
    ) {
        // A home screen has nothing to go back to: Back closes overlays, or exits move mode
        // without persisting, and is otherwise a no-op (the tv-detox precedent).
        BackHandler(enabled = state.editingKey != null) { onCloseEditMenu() }
        BackHandler(enabled = state.editingKey == null && state.movingKey != null) {
            onCancelMove()
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 96.dp, vertical = 64.dp),
            verticalArrangement = Arrangement.spacedBy(48.dp),
        ) {
            TvClockHeader(
                onOpenNetworkSettings = onOpenNetworkSettings,
            )
            TvAppGrid(
                state = state,
                onOpenTile = onOpenTile,
                onOpenEditMenu = onOpenEditMenu,
                onMoveKey = onMoveKey,
                onFinishMove = onFinishMove,
            )
            if (state.movingKey != null) {
                Text(
                    text = "Use the arrows to move it, then press OK",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xA8FFFFFF),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!state.loading) {
                TvBottomRow(
                    state = state,
                    onOpenPlayStore = onOpenPlayStore,
                    onOpenInput = onOpenInput,
                    onOpenSettings = onOpenSettings,
                    onOpenAddMenu = { onOpenAddMenu(-1) },
                )
            }
            if (!state.isDefaultHome && !state.loading) {
                TextButton(onClick = onRequestHomeRole) {
                    Text(
                        text = "Set as default home screen",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xA8FFFFFF),
                    )
                }
            }
        }
        TvEditMenu(
            state = state,
            onDismiss = onCloseEditMenu,
            onStartMove = onStartMove,
            onReplace = { onOpenAddMenu(state.rowApps.indexOfFirst { it.packageName == state.editingKey }) },
            onRemove = { state.editingKey?.let(onRemoveTile) },
            onUninstallOrInfo = { state.editingKey?.let(onUninstallOrInfo) },
            onPickAddCandidate = onPickAddCandidate,
            onCloseAddMenu = onCloseEditMenu,
        )
    }
}

/** Static warm gradient drawn once by the background: no extra view, no animation. */
private val TvHomeBackground = Brush.verticalGradient(
    colors = listOf(
        Color(0xFF15100F),
        Color(0xFF0A0809),
        Color(0xFF050405),
    ),
)
