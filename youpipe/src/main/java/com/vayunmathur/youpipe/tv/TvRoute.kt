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

    /** Fullscreen playback for one video. Pushed from detail/hero Play; BACK returns. */
    @Serializable
    data class VideoPlayerRoute(val videoID: Long, val resume: Boolean = false) : TvRoute

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

/** Rail indices in [TvRailHost]: Search is a destination, then the five browse roots. */
const val TV_RAIL_SEARCH = 0
const val TV_RAIL_HOME = 1
const val TV_RAIL_SUBSCRIPTIONS = 2
const val TV_RAIL_HISTORY = 3
const val TV_RAIL_SAVED = 4
const val TV_RAIL_SETTINGS = 5
const val TV_RAIL_COUNT = 6
