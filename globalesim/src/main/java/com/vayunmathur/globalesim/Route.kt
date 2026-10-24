package com.vayunmathur.globalesim

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    /** Tab host: Purchase + Pricing. */
    @Serializable
    data object Tabs : Route

    /** Install: direct LPA handoff (reissues the same activation code). */
    @Serializable
    data object Install : Route

    /** Move the eSIM to a new phone. */
    @Serializable
    data object Transfer : Route

    /** Credit balance + top-up history. */
    @Serializable
    data object Budget : Route
}
