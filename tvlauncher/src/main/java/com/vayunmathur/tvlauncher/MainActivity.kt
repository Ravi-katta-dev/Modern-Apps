package com.vayunmathur.tvlauncher

import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.tvlauncher.platform.TvHomeViewModel

/**
 * The TV home screen activity.
 *
 * `singleTask` plus `stateNotNeeded` in the manifest are what make it behave like a home
 * app: HOME delivers a new intent to the existing instance rather than stacking another
 * copy, and the system may kill it without saving state because the row order is in
 * DataStore, not in a `Bundle`. `edgeToEdge` is false: a TV has no phone chrome to draw
 * behind (the cast:tv precedent).
 */
class MainActivity : MaActivity() {
    override val edgeToEdge: Boolean = false

    private val viewModel: TvHomeViewModel by viewModels()
    private var backStack: NavBackStack<Route>? = null

    @Composable
    override fun Content() {
        val stack = rememberNavBackStack<Route>(Route.Home)
        SideEffect { backStack = stack }
        Navigation(viewModel, stack)
    }

    override fun onResume() {
        super.onResume()
        // Cheap enough to redo every time we come back: an app may have been installed or
        // uninstalled while we were away without us being alive to hear the callback.
        viewModel.refreshApps()
    }

    /**
     * Pressing HOME from inside another app comes back here. Resetting to the grid is what
     * makes HOME feel like HOME rather than a no-op (the :launcher MainActivity precedent).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        backStack?.reset(Route.Home)
    }
}
