package com.vayunmathur.games.kart

import androidx.compose.runtime.Composable
import com.vayunmathur.games.kart.platform.KartViewModel
import com.vayunmathur.games.kart.ui.KartGamePage
import com.vayunmathur.library.util.FullscreenPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack

@Composable
fun Navigation(viewModel: KartViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Game)
    MainNavigation(backStack) {
        entry<Route.Game>(metadata = FullscreenPage()) { KartGamePage(viewModel) }
    }
}
