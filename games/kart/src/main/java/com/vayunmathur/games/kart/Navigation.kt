package com.vayunmathur.games.kart

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.vayunmathur.games.kart.platform.KartViewModel
import com.vayunmathur.games.kart.ui.KartGamePage
import com.vayunmathur.games.kart.ui.SettingsPage
import com.vayunmathur.library.ui.AchievementNotification
import com.vayunmathur.library.ui.GameCenterScreen
import com.vayunmathur.library.util.GameHubComposeHook
import com.vayunmathur.library.util.FullscreenPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.openSettingsIfRequested
import com.vayunmathur.library.util.rememberNavBackStack

@Composable
fun Navigation(viewModel: KartViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Game)
    // Launching from App Info's settings cog deep-links straight to Settings.
    backStack.openSettingsIfRequested(Route.Settings)
    val newAchievement by viewModel.achievementsManager.newAchievement.collectAsState()
    GameHubComposeHook("kart", viewModel.achievementsManager)
    Box(Modifier.fillMaxSize()) {
        MainNavigation(backStack) {
            entry<Route.Game>(metadata = FullscreenPage()) { KartGamePage(backStack, viewModel) }
            entry<Route.GameCenter> {
                GameCenterScreen(
                    manager = viewModel.achievementsManager,
                    onBack = { backStack.pop() },
                )
            }
            entry<Route.Settings> {
                SettingsPage(viewModel = viewModel, onBack = { backStack.pop() })
            }
        }
        newAchievement?.let {
            AchievementNotification(it) { viewModel.dismissAchievementNotification() }
        }
    }
}
