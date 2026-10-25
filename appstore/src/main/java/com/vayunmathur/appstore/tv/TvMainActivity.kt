package com.vayunmathur.appstore.tv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.AppStoreDatabaseRepository
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.UpdateCheckWorker
import com.vayunmathur.library.tv.MaTvTheme
import com.vayunmathur.library.tv.TvAccent
import com.vayunmathur.library.tv.TvAppFrame
import com.vayunmathur.library.tv.TvDestination
import com.vayunmathur.library.tv.TvIcon
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconPackage
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconStore
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable

/**
 * TV-only destinations: the rail tabs plus one pushed detail.
 *
 * A separate sealed interface from the phone `Route`, following youpipe's `TvRoute`, so
 * a TV destination can never leak into the phone back stack or vice versa. Home, search,
 * updates and library are rail destinations — selecting one swaps the content, and each
 * keeps its own screen so focus restores per destination.
 */
@Serializable
sealed interface TvStoreRoute : NavKey {
    /** The rail: home, search, updates, library. D-pad only, no swipe. */
    @Serializable
    data object Main : TvStoreRoute

    /** One app's page, pushed over the rail. */
    @Serializable
    data class Detail(val packageName: String) : TvStoreRoute
}

/**
 * The TV entry: opened from TV launcher rows (LEANBACK_LAUNCHER). The phone MainActivity
 * stays exactly as it is — this activity hosts the D-pad store UI.
 *
 * Browse, search, updates, library and detail are nav-graph destinations. The ViewModel
 * is the phone repositories reused verbatim, so installs go through the ordinary
 * pipeline including the TV-compat gate.
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
        // Always dark with the store's teal accent: a TV has no wallpaper to derive a
        // scheme from, and the accent marks this TV app at a glance.
        MaTvTheme(accent = TvAccent.Teal, content = content)
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
 * The detail destination reads the selection the rail already set via
 * [TvStoreViewModel.selectApp] — popping clears it, so BACK from detail always lands on
 * the destination that opened it with no stale selection behind it.
 */
@Composable
private fun TvStoreNavigation(viewModel: TvStoreViewModel) {
    val backStack = rememberNavBackStack<TvStoreRoute>(TvStoreRoute.Main)
    MainNavigation(backStack) {
        entry<TvStoreRoute.Main> {
            TvStoreRail(backStack = backStack, viewModel = viewModel)
        }
        entry<TvStoreRoute.Detail> {
            TvStoreDetail(
                viewModel = viewModel,
                onBack = {
                    backStack.pop()
                    viewModel.clearSelection()
                },
            )
        }
    }
}

/** Rail destination indices, matching the destinations list order below. */
private const val TV_DEST_HOME = 0
private const val TV_DEST_SEARCH = 1
private const val TV_DEST_UPDATES = 2
private const val TV_DEST_LIBRARY = 3

/**
 * The store shell: a [TvAppFrame] rail (Home, Search, Updates with count, Library) with
 * the store brand, and the selected destination's screen beside it.
 */
@Composable
private fun TvStoreRail(
    backStack: NavBackStack<TvStoreRoute>,
    viewModel: TvStoreViewModel,
) {
    val updates by viewModel.updatesUi.collectAsState()
    val updateCount = updates.updates.size
    // @Composable string resolutions hoisted: the destination list below is plain data.
    val homeLabel = stringResource(R.string.nav_home)
    val searchLabel = stringResource(R.string.nav_search)
    val updatesLabel = if (updateCount > 0) {
        stringResource(R.string.tv_updates_with_count, updateCount)
    } else {
        stringResource(R.string.nav_updates)
    }
    val libraryLabel = stringResource(R.string.nav_library)
    val destinations = remember(homeLabel, searchLabel, updatesLabel, libraryLabel) {
        listOf(
            TvDestination(homeLabel) { IconHome() },
            TvDestination(searchLabel) { IconSearch() },
            TvDestination(updatesLabel) { IconDownload() },
            TvDestination(libraryLabel) { IconPackage() },
        )
    }
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }

    fun openDetail(app: UnifiedApp) {
        viewModel.selectApp(app)
        backStack.add(TvStoreRoute.Detail(app.packageName))
    }

    TvAppFrame(
        destinations = destinations,
        selected = selectedIndex,
        onSelect = { selectedIndex = it },
        brand = { TvStoreBrand() },
        backReturnsToRail = backStack.backStack.size <= 1,
    ) {
        when (selectedIndex) {
            TV_DEST_SEARCH -> TvStoreSearch(
                viewModel = viewModel,
                onAppClick = ::openDetail,
                // The keyboard opens only on an explicit CENTER press on the field;
                // arriving via the rail never starts editing.
                startEditing = false,
            )
            TV_DEST_UPDATES -> TvStoreUpdates(viewModel = viewModel, onAppClick = ::openDetail)
            TV_DEST_LIBRARY -> TvStoreLibrary(viewModel = viewModel, onAppClick = ::openDetail)
            else -> TvStoreHome(viewModel = viewModel, onAppClick = ::openDetail)
        }
    }
}

/** Brand slot: store icon + "App Store" text, shown when the rail expands. */
@Composable
private fun TvStoreBrand(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            TvIcon { IconStore() }
        }
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
