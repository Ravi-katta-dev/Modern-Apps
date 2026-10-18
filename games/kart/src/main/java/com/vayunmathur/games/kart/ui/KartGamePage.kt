package com.vayunmathur.games.kart.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.games.kart.platform.KartViewModel

/**
 * Binds [KartViewModel] to the stateless [KartGameScreen].
 *
 * The ViewModel already implements the actions contract, so this only has to collect state.
 */
@Composable
fun KartGamePage(
    viewModel: KartViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    val racePhase by viewModel.racePhase.collectAsState()
    KartGameScreen(
        state = uiState,
        phase = racePhase,
        actions = viewModel,
    )
}
