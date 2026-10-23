package com.vayunmathur.uniflix

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.util.FullscreenPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.uniflix.domain.MediaType
import com.vayunmathur.uniflix.platform.AddonsViewModel
import com.vayunmathur.uniflix.platform.BrowseViewModel
import com.vayunmathur.uniflix.platform.DetailViewModel
import com.vayunmathur.uniflix.platform.HomeViewModel
import com.vayunmathur.uniflix.platform.ListsViewModel
import com.vayunmathur.uniflix.platform.SearchViewModel
import com.vayunmathur.uniflix.ui.AddonDetailScreen
import com.vayunmathur.uniflix.ui.AddonsScreen
import com.vayunmathur.uniflix.ui.ContinueWatchingScreen
import com.vayunmathur.uniflix.ui.DetailScreen
import com.vayunmathur.uniflix.ui.HomeScreen
import com.vayunmathur.uniflix.ui.ListsScreen
import com.vayunmathur.uniflix.ui.MediaGridScreen
import com.vayunmathur.uniflix.ui.PlayerSettingsScreen
import com.vayunmathur.uniflix.ui.SearchScreen
import com.vayunmathur.uniflix.ui.WatchScreen

private fun mediaTypeOf(raw: String): MediaType =
    if (raw == "tv") MediaType.TV else MediaType.MOVIE

@Composable
fun Navigation() {
    val backStack = rememberNavBackStack<Route>(Route.Home)

    MainNavigation(backStack) {
        entry<Route.Home> {
            val vm: HomeViewModel = viewModel()
            HomeScreen(
                state = vm.state,
                onRefresh = { vm.refresh() },
                onOpenDetail = { item ->
                    backStack.add(Route.Detail(item.mediaType.name.lowercase(), item.tmdbId))
                },
                onOpenSearch = { backStack.add(Route.Search) },
                onOpenMovies = { backStack.add(Route.Movies) },
                onOpenSeries = { backStack.add(Route.Series) },
                onOpenLists = { backStack.add(Route.Lists) },
                onOpenContinue = { backStack.add(Route.ContinueWatching) },
                onOpenAddons = { backStack.add(Route.Addons) },
            )
        }
        entry<Route.Movies> {
            val vm: BrowseViewModel = viewModel()
            LaunchedEffect(Unit) { vm.loadMovies() }
            MediaGridScreen(
                title = "Movies",
                loading = vm.movies.loading,
                items = vm.movies.items,
                onBack = { backStack.pop() },
                onOpenDetail = { item ->
                    backStack.add(Route.Detail(item.mediaType.name.lowercase(), item.tmdbId))
                },
            )
        }
        entry<Route.Series> {
            val vm: BrowseViewModel = viewModel()
            LaunchedEffect(Unit) { vm.loadSeries() }
            MediaGridScreen(
                title = "Series",
                loading = vm.series.loading,
                items = vm.series.items,
                onBack = { backStack.pop() },
                onOpenDetail = { item ->
                    backStack.add(Route.Detail(item.mediaType.name.lowercase(), item.tmdbId))
                },
            )
        }
        entry<Route.Search> {
            val vm: SearchViewModel = viewModel()
            SearchScreen(
                state = vm.state,
                onQueryChange = vm::onQueryChange,
                onBack = { backStack.pop() },
                onOpenDetail = { item ->
                    backStack.add(Route.Detail(item.mediaType.name.lowercase(), item.tmdbId))
                },
            )
        }
        entry<Route.Detail> { route ->
            val vm: DetailViewModel = viewModel()
            val type = mediaTypeOf(route.mediaType)
            LaunchedEffect(route.tmdbId, route.mediaType) { vm.load(type, route.tmdbId) }
            DetailScreen(
                state = vm.state,
                onBack = { backStack.pop() },
                onSeasonChange = { season -> vm.selectSeason(type, route.tmdbId, season) },
                onToggleWatchlist = vm::toggleWatchlist,
                onPlay = { season, episode ->
                    backStack.add(Route.Watch(route.mediaType, route.tmdbId, season, episode))
                },
                onResolve = { season, episode -> vm.resolve(type, route.tmdbId, season, episode) },
                onAddNote = { body, score -> vm.addNote(type, route.tmdbId, body, score) },
                onOpenDetail = { item ->
                    backStack.add(Route.Detail(item.mediaType.name.lowercase(), item.tmdbId))
                },
                onOpenAddons = { backStack.add(Route.Addons) },
            )
        }
        entry<Route.Watch>(metadata = FullscreenPage()) { route ->
            val vm: DetailViewModel = viewModel()
            val type = mediaTypeOf(route.mediaType)
            var startPositionMs by remember(route.tmdbId, route.season, route.episode) {
                mutableLongStateOf(0L)
            }
            LaunchedEffect(route.tmdbId, route.season, route.episode) {
                vm.load(type, route.tmdbId)
                vm.loadServers()
                startPositionMs = vm.startPosition(type, route.tmdbId, route.season, route.episode)
                vm.resolve(type, route.tmdbId, route.season, route.episode)
            }
            WatchScreen(
                state = vm.state.copy(startPositionMs = startPositionMs),
                mediaType = type,
                season = route.season,
                episode = route.episode,
                onBack = { backStack.pop() },
                onRetry = { vm.resolve(type, route.tmdbId, route.season, route.episode) },
                onOpenAddons = { backStack.add(Route.Addons) },
                onSelectServer = { server ->
                    vm.selectServer(type, route.tmdbId, route.season, route.episode, server)
                },
                onSavePosition = { positionMs, durationMs ->
                    val detail = vm.state.detail ?: return@WatchScreen
                    vm.savePosition(
                        type, route.tmdbId, detail.title, detail.posterPath,
                        route.season, route.episode, positionMs, durationMs,
                    )
                },
            )
        }
        entry<Route.Lists> {
            val vm: ListsViewModel = viewModel()
            ListsScreen(
                onBack = { backStack.pop() },
                onOpenDetail = { item ->
                    backStack.add(Route.Detail(item.mediaType.name.lowercase(), item.tmdbId))
                },
            )
        }
        entry<Route.ContinueWatching> {
            ContinueWatchingScreen(
                onBack = { backStack.pop() },
                onResume = { entry ->
                    backStack.add(Route.Watch(entry.mediaType, entry.tmdbId, entry.season, entry.episode))
                },
            )
        }
        entry<Route.PlayerSettings> {
            PlayerSettingsScreen(onBack = { backStack.pop() })
        }
        entry<Route.Addons> {
            val vm: AddonsViewModel = viewModel()
            AddonsScreen(
                onBack = { backStack.pop() },
                onOpenDetail = { dbId -> backStack.add(Route.AddonDetail(dbId)) },
            )
        }
        entry<Route.AddonDetail> { route ->
            AddonDetailScreen(
                addonDbId = route.addonDbId,
                onBack = { backStack.pop() },
            )
        }
    }
}
