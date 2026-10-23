package com.vayunmathur.uniflix

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Home : Route

    @Serializable
    data object Movies : Route

    @Serializable
    data object Series : Route

    @Serializable
    data object Search : Route

    @Serializable
    data class Detail(val mediaType: String, val tmdbId: Int) : Route

    @Serializable
    data class Watch(val mediaType: String, val tmdbId: Int, val season: Int = 0, val episode: Int = 0) : Route

    @Serializable
    data object Lists : Route

    @Serializable
    data object ContinueWatching : Route

    @Serializable
    data object PlayerSettings : Route

    @Serializable
    data object Addons : Route

    @Serializable
    data class AddonDetail(val addonDbId: Long) : Route
}
