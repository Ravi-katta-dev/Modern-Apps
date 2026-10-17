package com.vayunmathur.games.kart

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    /** The garage screen: which karts/tracks are unlocked and what the next race looks like. */
    @Serializable
    data object Game : Route

    @Serializable
    data object GameCenter : Route

    @Serializable
    data object Settings : Route
}
