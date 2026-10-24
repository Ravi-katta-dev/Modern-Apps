package com.vayunmathur.globalesim

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    /** Tab host: Purchase + Pricing. */
    @Serializable
    data object Tabs : Route

    /** Move the eSIM to a new phone. */
    @Serializable
    data object Transfer : Route
}
