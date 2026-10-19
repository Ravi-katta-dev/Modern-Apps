package com.vayunmathur.games.hub.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.games.hub.data.entities.YuuFaceConfig
import com.vayunmathur.games.hub.ui.yuu.YuuEditorActions
import com.vayunmathur.games.hub.ui.yuu.YuuEditorUiState
import com.vayunmathur.games.hub.viewmodel.GameHubViewModel

/** Binds [GameHubViewModel] to the stateless [YuuEditorScreen]. */
@Composable
fun YuuEditorPage(
    viewModel: GameHubViewModel,
    editingId: Long?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val yuus by viewModel.yuuListFlow.collectAsStateWithLifecycle()
    val editing = remember(editingId, yuus) { yuus.find { it.id == editingId } }

    YuuEditorScreen(
        state = YuuEditorUiState(
            editingId = editingId,
            initialName = editing?.name ?: "",
            initialConfig = editing?.config ?: YuuFaceConfig.DEFAULT,
        ),
        actions = object : YuuEditorActions {
            override fun onSave(name: String, config: YuuFaceConfig) {
                val trimmed = name.trim()
                if (editingId == null) {
                    viewModel.createYuu(trimmed, config)
                } else {
                    viewModel.updateYuu(editingId, trimmed, config)
                }
                onDismiss()
            }
            override fun onDismiss() = onDismiss()
        },
        modifier = modifier,
    )
}
