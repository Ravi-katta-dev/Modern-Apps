package com.vayunmathur.youpipe.tv

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/**
 * TV navigation graph: mirrors the phone [com.vayunmathur.youpipe.Route] destinations a D-pad
 * can reach, without the phone pager's swipe or its search overlay.
 *
 * The phone graph stays exactly as it is — this is a separate sealed interface so a TV
 * destination can never leak into the phone back stack or vice versa. Dialogs (add-to-playlist,
 * create-playlist, create-category) are entries here rather than overlays, because a TV has no
 * bottom sheet to host them: each is a full D-pad-traversable list screen with its own focus.
 */
@Serializable
sealed interface TvRoute : NavKey {
    /** The tab strip: home feed, subscriptions, history, saved, settings. No swipe, D-pad only. */
    @Serializable
    data class Main(val initialTab: Int = 0) : TvRoute

    /** Explicit search screen: opened only from the home entry row's CENTER press, never autofocused. */
    @Serializable
    data object Search : TvRoute

    @Serializable
    data class Video(val videoID: Long) : TvRoute

    @Serializable
    data class Channel(val channelID: String) : TvRoute

    @Serializable
    data class SubscriptionFeed(val category: String?) : TvRoute

    @Serializable
    data class CreateSubscriptionCategory(val id: String?) : TvRoute

    @Serializable
    data object Downloads : TvRoute

    @Serializable
    data class PlaylistDetail(val playlistId: Long) : TvRoute

    @Serializable
    data object CreatePlaylist : TvRoute

    /** Staged membership checkboxes as a focusable list; confirms with the focused OK row. */
    @Serializable
    data class AddToPlaylist(val videoID: Long, val includeWatchLater: Boolean = true) : TvRoute

    @Serializable
    data object SettingsGeneral : TvRoute

    @Serializable
    data object SettingsSponsorBlock : TvRoute

    @Serializable
    data object SettingsRecommendations : TvRoute

    @Serializable
    data object SettingsData : TvRoute

    @Serializable
    data object ManageInterests : TvRoute
}

/** Tab indices in [TvMainTabs], matching the phone's five tabs minus swipe. */
const val TV_TAB_HOME = 0
const val TV_TAB_SUBSCRIPTIONS = 1
const val TV_TAB_HISTORY = 2
const val TV_TAB_SAVED = 3
const val TV_TAB_SETTINGS = 4
