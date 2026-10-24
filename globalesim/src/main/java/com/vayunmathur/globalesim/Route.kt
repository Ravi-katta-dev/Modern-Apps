package com.vayunmathur.globalesim

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    /** Home: your eSIM status + wallet + top-up. */
    @Serializable
    data object Home : Route

    /** Per-country rate list (what each GB deducts where you travel). */
    @Serializable
    data object Rates : Route

    /** Install detail: QR + handoff (reissues the same activation code). */
    @Serializable
    data object Install : Route

    /** Move the eSIM to a new phone. */
    @Serializable
    data object Transfer : Route

    /** Credit balance + top-up history. */
    @Serializable
    data object Budget : Route
}
