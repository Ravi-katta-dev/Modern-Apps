package com.vayunmathur.games.kart.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.games.kart.Route
import com.vayunmathur.games.kart.platform.KartViewModel
import com.vayunmathur.library.util.NavBackStack

/**
 * Binds [KartViewModel] to the stateless [KartGameScreen].
 *
 * The ViewModel already implements the actions contract, so this only has to collect state and supply
 * the two navigation callbacks the garage cannot know about.
 */
@Composable
fun KartGamePage(
    backStack: NavBackStack<Route>,
    viewModel: KartViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    val racePhase by viewModel.racePhase.collectAsState()
    KartGameScreen(
        state = uiState,
        phase = racePhase,
        actions = viewModel,
        onOpenSettings = { backStack.add(Route.Settings) },
        onOpenGameCenter = { backStack.add(Route.GameCenter) },
    )
}
