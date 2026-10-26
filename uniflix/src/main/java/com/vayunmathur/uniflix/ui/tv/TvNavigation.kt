package com.vayunmathur.uniflix.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.tv.TvAppFrame
import com.vayunmathur.library.tv.TvDestination
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconList
import com.vayunmathur.library.ui.IconMovie
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconTv
import com.vayunmathur.library.ui.IconWidgets
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.FullscreenPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.uniflix.R
import com.vayunmathur.uniflix.domain.MediaType

/**
 * The TV navigation host: [rememberNavBackStack] + [MainNavigation] over [TvRoute].
 *
 * The rail tabs (search, home, movies, shows, my list, addons) live inside one
 * [TvAppFrame]; detail, watch and addon-detail push on top without the rail, so
 * BACK from them returns to the tab that opened them.
 */
@Composable
fun TvNavigation() {
    val backStack = rememberNavBackStack<TvRoute>(TvRoute.Main())
    MainNavigation(backStack) {
        entry<TvRoute.Main> { route -> TvMainTabs(backStack, route.initialTab) }
        entry<TvRoute.Detail> { route ->
            TvDetailPage(backStack, route.mediaType, route.tmdbId)
        }
        entry<TvRoute.Grid> { route ->
            TvGridPage(backStack, route.kind)
        }
        entry<TvRoute.Watch>(metadata = FullscreenPage()) { route ->
            TvWatchPage(backStack, route.mediaType, route.tmdbId, route.season, route.episode)
        }
        entry<TvRoute.AddonDetail> { route ->
            TvAddonDetailPage(backStack, route.addonDbId)
        }
    }
}

private fun openDetail(backStack: NavBackStack<TvRoute>, mediaType: MediaType, tmdbId: Int) {
    backStack.add(TvRoute.Detail(mediaType.name.lowercase(), tmdbId))
}

/**
 * The six rail tabs: search, home, movies, shows, my list, addons.
 *
 * Each tab keeps its own screen (and ViewModel) so focus restores per tab.
 * BACK on a root tab returns to the rail; BACK from the rail exits.
 */
@Composable
private fun TvMainTabs(backStack: NavBackStack<TvRoute>, initialTab: Int) {
    // Saveable so BACK from Detail/Watch restores the tab; keyed on initialTab so
    // setLast(Main(ADDONS)) still switches it.
    var tab by rememberSaveable(initialTab) { mutableIntStateOf(initialTab.coerceIn(TV_TAB_SEARCH, TV_TAB_ADDONS)) }
    TvAppFrame(
        destinations = railDestinations(),
        selected = tab,
        onSelect = { tab = it },
        backReturnsToRail = backStack.backStack.size <= 1,
    ) {
        when (tab) {
            TV_TAB_SEARCH -> TvSearchTab(
                onOpenDetail = { openDetail(backStack, it.mediaType, it.tmdbId) },
            )
            TV_TAB_HOME -> TvHomeTab(
                onOpenDetail = { openDetail(backStack, it.mediaType, it.tmdbId) },
                onResume = { entry ->
                    backStack.add(TvRoute.Watch(entry.mediaType, entry.tmdbId, entry.season, entry.episode))
                },
                onOpenGrid = { kind -> backStack.add(TvRoute.Grid(kind)) },
                onOpenTab = { tab = it },
            )
            TV_TAB_MOVIES -> TvBrowseTab(
                mediaType = MediaType.MOVIE,
                onOpenDetail = { openDetail(backStack, it.mediaType, it.tmdbId) },
            )
            TV_TAB_SHOWS -> TvBrowseTab(
                mediaType = MediaType.TV,
                onOpenDetail = { openDetail(backStack, it.mediaType, it.tmdbId) },
            )
            TV_TAB_MY_LIST -> TvMyListTab(
                onOpenDetail = { openDetail(backStack, it.mediaType, it.tmdbId) },
                onResume = { entry ->
                    backStack.add(TvRoute.Watch(entry.mediaType, entry.tmdbId, entry.season, entry.episode))
                },
            )
            else -> TvAddonsTab(
                onOpenDetail = { dbId -> backStack.add(TvRoute.AddonDetail(dbId)) },
            )
        }
    }
}

@Composable
private fun railDestinations(): List<TvDestination> {
    val search = stringResource(UiR.string.search)
    val home = stringResource(UiR.string.home)
    val movies = stringResource(R.string.movies)
    val shows = stringResource(R.string.shows)
    val myList = stringResource(R.string.my_list)
    val addons = stringResource(R.string.stream_addons)
    return remember(search, home, movies, shows, myList, addons) {
        listOf(
            TvDestination(search, { IconSearch() }),
            TvDestination(home, { IconHome() }),
            TvDestination(movies, { IconMovie() }),
            TvDestination(shows, { IconTv() }),
            TvDestination(myList, { IconList() }),
            TvDestination(addons, { IconWidgets() }),
        )
    }
}
