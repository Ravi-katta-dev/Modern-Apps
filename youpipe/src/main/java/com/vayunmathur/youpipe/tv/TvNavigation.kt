package com.vayunmathur.youpipe.tv

import androidx.activity.compose.BackHandler
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
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.util.YouPipeViewModel

/**
 * The TV navigation host: [rememberNavBackStack] + [MainNavigation] over [TvRoute], replacing
 * the phone's 5-tab swipe pager.
 *
 * No `HorizontalPager` swipe — a TV has no touch — and no shared-element morphs: the phone's
 * title keys assume single-origin compositions the tab pager already strains, and TV screens
 * compose independently. Tabs are a focusable [TabRow] strip; D-pad LEFT/RIGHT moves between
 * tabs, DOWN drops into content. Each tab keeps its own screen so focus restores per tab.
 */
@Composable
fun TvNavigation(ypvm: YouPipeViewModel) {
    val backStack = rememberNavBackStack<TvRoute>(TvRoute.Main())
    MainNavigation(backStack) {
        entry<TvRoute.Main> { TvMainTabs(backStack, ypvm, it.initialTab) }
        entry<TvRoute.Search> { TvSearchPage(backStack, ypvm) }
        entry<TvRoute.Video> { TvVideoPage(backStack, ypvm, it.videoID) }
        entry<TvRoute.Channel> { TvChannelPage(backStack, ypvm, it.channelID) }
        entry<TvRoute.SubscriptionFeed> { TvSubscriptionFeedPage(backStack, ypvm, it.category) }
        entry<TvRoute.CreateSubscriptionCategory> { TvCreateCategoryPage(backStack, ypvm, it.id) }
        entry<TvRoute.Downloads> { TvDownloadsPage(backStack, ypvm) }
        entry<TvRoute.PlaylistDetail> { TvPlaylistDetailPage(backStack, ypvm, it.playlistId) }
        entry<TvRoute.CreatePlaylist> { TvCreatePlaylistPage(backStack, ypvm) }
        entry<TvRoute.AddToPlaylist> { TvAddToPlaylistPage(backStack, ypvm, it.videoID, it.includeWatchLater) }
        entry<TvRoute.SettingsGeneral> { TvGeneralSettingsPage(backStack, ypvm) }
        entry<TvRoute.SettingsSponsorBlock> { TvSponsorBlockSettingsPage(backStack, ypvm) }
        entry<TvRoute.SettingsRecommendations> { TvRecommendationsSettingsPage(backStack, ypvm) }
        entry<TvRoute.SettingsData> { TvDataSettingsPage(backStack, ypvm) }
        entry<TvRoute.ManageInterests> { TvManageInterestsPage(backStack, ypvm) }
    }
}

/**
 * The five TV tabs as a D-pad strip: Home, Subscriptions, History, Saved, Settings.
 *
 * Unlike the phone's `TabbedPagerScaffold`, tabs are destinations only — selecting one swaps
 * the content below without any swipe gesture. Per-tab focus restores because each tab's
 * screen owns its own list state.
 */
@Composable
private fun TvMainTabs(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
    initialTab: Int,
) {
    var tab by remember { mutableIntStateOf(initialTab.coerceIn(0, 4)) }
    val homeLabel = stringResource(UiR.string.home)
    val subsLabel = stringResource(R.string.title_subscriptions)
    val historyLabel = stringResource(R.string.title_history)
    val savedLabel = stringResource(UiR.string.saved)
    val settingsLabel = stringResource(UiR.string.settings)

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TabRow(selectedTabIndex = tab) {
            for (i in 0..4) {
                Tab(
                    selected = tab == i,
                    onFocus = { tab = i },
                    onClick = { tab = i },
                ) {
                    androidx.tv.material3.Text(
                        tvLabelForTab(i, homeLabel, subsLabel, historyLabel, savedLabel, settingsLabel),
                    )
                }
            }
        }
        when (tab) {
            TV_TAB_HOME -> TvHomeFeedPage(backStack, ypvm)
            TV_TAB_SUBSCRIPTIONS -> TvSubscriptionsPage(backStack, ypvm)
            TV_TAB_HISTORY -> TvHistoryPage(backStack, ypvm)
            TV_TAB_SAVED -> TvSavedPage(backStack, ypvm)
            else -> TvSettingsPage(backStack, ypvm)
        }
    }
    // BACK on a root tab exits the app (the activity finishes); deeper routes pop via nav3.
    BackHandler(enabled = backStack.backStack.size <= 1) {
        // No-op: with nothing to pop, the activity handles BACK as finish.
    }
}
