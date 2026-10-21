package com.vayunmathur.appstore.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.AppStoreDatabaseRepository
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.UpdateCheckWorker
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable

/**
 * TV-only destinations: browse/search/updates tabs plus one pushed detail.
 *
 * A separate sealed interface from the phone [com.vayunmathur.appstore.Route], following
 * youpipe's `TvRoute`, so a TV destination can never leak into the phone back stack or
 * vice versa. Search and updates are tabs here rather than pushed routes: on a D-pad the
 * tab strip is the entry point, and each tab keeps its own screen so focus restores per tab.
 */
@Serializable
sealed interface TvStoreRoute : NavKey {
    /** The tab strip: browse, search, updates. No swipe, D-pad only. */
    @Serializable
    data object Main : TvStoreRoute

    /** One app's page, pushed over the tabs. */
    @Serializable
    data class Detail(val packageName: String) : TvStoreRoute
}

/**
 * The TV entry: opened from TV launcher rows (LEANBACK_LAUNCHER). The phone MainActivity
 * stays exactly as it is — this activity hosts the D-pad store UI.
 *
 * Browse, search, updates, and detail are nav-graph destinations, not activity states: the
 * tab strip owns browse/search/updates, and detail pushes over it. The ViewModel is the
 * phone repositories reused verbatim, so installs go through the ordinary pipeline
 * including the TV-compat gate.
 */
class TvMainActivity : MaActivity() {
    override val edgeToEdge: Boolean = false

    override fun onCreateBeforeContent() {
        // Same arming the phone does: keep the 12-hour update check scheduled from the TV
        // entry too, so a TV-driven install still gets its update notifications — which
        // route back here (see UpdateCheckWorker).
        UpdateCheckWorker.schedule(this)
    }

    @Composable
    override fun AppTheme(content: @Composable () -> Unit) {
        // Always dark, not dynamic: a TV has no wallpaper to derive a scheme from.
        // Paints nothing itself; screens set their own background.
        MaterialTheme(colorScheme = darkColorScheme(), content = content)
    }

    @Composable
    override fun Content() {
        val repository = remember { AppStoreDatabaseRepository.get(this) }
        val factory = remember { TvStoreViewModelFactory(this, repository.database) }
        val viewModel: TvStoreViewModel = viewModel(factory = factory)
        TvStoreNavigation(viewModel = viewModel)
    }
}

/**
 * The TV navigation host: [rememberNavBackStack] + [MainNavigation] over [TvStoreRoute].
 *
 * The detail destination reads the selection the tab already set via
 * [TvStoreViewModel.selectApp] — popping clears it, so BACK from detail always lands on
 * the tab that opened it with no stale selection behind it.
 */
@Composable
private fun TvStoreNavigation(viewModel: TvStoreViewModel) {
    val backStack = rememberNavBackStack<TvStoreRoute>(TvStoreRoute.Main)
    MainNavigation(backStack) {
        entry<TvStoreRoute.Main> {
            TvStoreTabs(backStack = backStack, viewModel = viewModel)
        }
        entry<TvStoreRoute.Detail> {
            TvAppDetail(
                viewModel = viewModel,
                onBack = {
                    backStack.pop()
                    viewModel.clearSelection()
                },
            )
        }
    }
}

/**
 * The three TV tabs as a D-pad strip: Browse, Search, Updates.
 *
 * Unlike the phone's `TabbedPagerScaffold`, tabs are destinations only — selecting one swaps
 * the content below without any swipe gesture. Per-tab focus restores because each tab's
 * screen owns its own list state.
 */
@Composable
private fun TvStoreTabs(
    backStack: NavBackStack<TvStoreRoute>,
    viewModel: TvStoreViewModel,
) {
    var tab by remember { mutableIntStateOf(TV_TAB_BROWSE) }

    fun openDetail(app: UnifiedApp) {
        viewModel.selectApp(app)
        backStack.add(TvStoreRoute.Detail(app.packageName))
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == TV_TAB_BROWSE,
                onFocus = { tab = TV_TAB_BROWSE },
                onClick = { tab = TV_TAB_BROWSE },
            ) {
                Text(stringResource(R.string.nav_home))
            }
            Tab(
                selected = tab == TV_TAB_SEARCH,
                onFocus = { tab = TV_TAB_SEARCH },
                onClick = { tab = TV_TAB_SEARCH },
            ) {
                Text(stringResource(R.string.nav_search))
            }
            Tab(
                selected = tab == TV_TAB_UPDATES,
                onFocus = { tab = TV_TAB_UPDATES },
                onClick = { tab = TV_TAB_UPDATES },
            ) {
                Text(stringResource(R.string.nav_updates))
            }
        }
        when (tab) {
            TV_TAB_SEARCH -> TvStoreSearch(viewModel = viewModel, onAppClick = ::openDetail)
            TV_TAB_UPDATES -> TvStoreUpdates(viewModel = viewModel, onAppClick = ::openDetail)
            else -> TvStoreHome(viewModel = viewModel, onAppClick = ::openDetail)
        }
    }
}

private const val TV_TAB_BROWSE = 0
private const val TV_TAB_SEARCH = 1
private const val TV_TAB_UPDATES = 2
