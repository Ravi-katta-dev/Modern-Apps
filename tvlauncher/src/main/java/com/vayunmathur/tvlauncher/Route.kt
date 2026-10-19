package com.vayunmathur.tvlauncher

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/**
 * The nav graph is a single destination in v1.
 *
 * The edit menu (move/replace/remove) and the add-app picker are **not** routes: they are
 * overlays inside the home screen, because D-pad focus must stay inside one focus tree while
 * editing (the :launcher drawer-as-overlay precedent, minus the drag system).
 */
@Serializable
sealed interface Route : NavKey {
    /** The banner grid with the clock header and bottom row. */
    @Serializable
    data object Home : Route
}
