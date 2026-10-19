package com.vayunmathur.games.hub.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.games.hub.data.YuuUiModel
import com.vayunmathur.games.hub.ui.yuu.YuuListActions
import com.vayunmathur.games.hub.ui.yuu.YuuListUiState
import com.vayunmathur.games.hub.viewmodel.GameHubViewModel

/** Binds [GameHubViewModel] to the stateless [YuuScreen]. */
@Composable
fun YuuPage(
    viewModel: GameHubViewModel,
    onCreateYuu: () -> Unit,
    onEditYuu: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val yuus by viewModel.yuuListFlow.collectAsStateWithLifecycle()
    val profileYuu by viewModel.profileYuuFlow.collectAsStateWithLifecycle()

    YuuScreen(
        state = YuuListUiState(
            yuus = yuus,
            profileYuuId = profileYuu?.id,
        ),
        actions = object : YuuListActions {
            override fun onCreate() = onCreateYuu()
            override fun onEdit(yuu: YuuUiModel) = onEditYuu(yuu.id)
            override fun onDuplicate(yuu: YuuUiModel) = viewModel.duplicateYuu(yuu.id)
            override fun onDelete(yuu: YuuUiModel) = viewModel.deleteYuu(yuu.id)
            override fun onSetAsProfile(yuu: YuuUiModel) = viewModel.setProfileYuu(yuu.id)
            override fun onClearProfile() = viewModel.clearProfileYuu()
        },
        modifier = modifier,
    )
}
