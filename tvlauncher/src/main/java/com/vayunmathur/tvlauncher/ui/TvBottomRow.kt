package com.vayunmathur.tvlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconHdmi
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.animatedFloat
import com.vayunmathur.tvlauncher.domain.TvRowOrder
import com.vayunmathur.tvlauncher.platform.TvHdmiInput
import com.vayunmathur.tvlauncher.platform.TvHomeState

/**
 * The bottom glass row: Play Store, HDMI inputs, Settings, Add (the tv-detox bottom row).
 *
 * Glass tiles with icon above label; focused tiles lift to ~1.1 like the banner grid.
 */
@Composable
fun TvBottomRow(
    state: TvHomeState,
    onOpenPlayStore: () -> Unit,
    onOpenInput: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAddMenu: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(36.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (state.showPlayStore) {
            TvGlassTile(
                label = state.playStoreLabel.ifBlank { "Play Store" },
                onClick = onOpenPlayStore,
                modifier = Modifier.weight(1f),
            ) {
                IconPlay(modifier = Modifier.size(44.dp), tint = Color.White)
            }
        }
        for (input in state.hdmiInputs) {
            key(input.id) {
                TvHdmiTile(
                    input = input,
                    onClick = { onOpenInput(input.id) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        TvGlassTile(
            label = "Settings",
            onClick = onOpenSettings,
            modifier = Modifier.weight(1f),
        ) {
            IconSettings(modifier = Modifier.size(44.dp), tint = Color.White)
        }
        TvGlassTile(
            label = "Add app",
            onClick = onOpenAddMenu,
            modifier = Modifier.weight(1f),
        ) {
            IconAdd(modifier = Modifier.size(44.dp), tint = Color.White)
        }
        // Keep the row's rhythm when fewer than PER_ROW tiles show: spacers hold the width so
        // the grid above and this row stay aligned (tv-detox always lays out a full row).
        val shown = (if (state.showPlayStore) 1 else 0) + state.hdmiInputs.size + 2
        repeat((TvRowOrder.PER_ROW - shown).coerceAtLeast(0)) {
            Box(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun TvHdmiTile(
    input: TvHdmiInput,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvGlassTile(
        label = input.label,
        onClick = onClick,
        modifier = modifier,
    ) {
        IconHdmi(modifier = Modifier.size(44.dp), tint = Color.White)
    }
}

@Composable
private fun TvGlassTile(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale = animatedFloat(if (focused) 1.1f else 1f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .aspectRatio(16f / 9f)
            .scale(scale)
            .clip(MaterialTheme.shapes.largeIncreased)
            .background(Color(0x13FFFFFF))
            .border(
                width = 2.dp,
                color = if (focused) Color(0x38FFFFFF) else Color(0x17FFFFFF),
                shape = MaterialTheme.shapes.largeIncreased,
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) { icon() }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = Color(0xE0FFFFFF),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
