package com.vayunmathur.tvlauncher

import androidx.compose.runtime.Composable
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.tvlauncher.platform.TvHomeViewModel
import com.vayunmathur.tvlauncher.ui.TvHomePage

@Composable
fun Navigation(
    viewModel: TvHomeViewModel,
    backStack: NavBackStack<Route>,
) {
    // Opaque: a TV home screen paints its own warm gradient, never the wallpaper behind it.
    MainNavigation(backStack) {
        entry<Route.Home> { TvHomePage(viewModel) }
    }
}
