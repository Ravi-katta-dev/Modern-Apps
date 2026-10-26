package com.vayunmathur.cast.tv.ui

import androidx.compose.runtime.Composable
import com.vayunmathur.library.tv.MaTvTheme
import com.vayunmathur.library.tv.TvAccent

/**
 * The theme for every screen in this app.
 *
 * Always dark, keyed off the Cast blue accent, and shared with the other TV apps through
 * `:library:tv` rather than a local colour scheme.
 *
 * **Paints nothing.** Deliberately not `Surface`, and not a `Box` with the scheme's background:
 * one of the two things wrapped in this theme is an overlay above a decoder surface, and anything
 * opaque here would be a solid rectangle over the video for the whole session. `MaTvTheme`
 * only installs colour schemes and content colours, so that property carries over. Screens
 * that want a background say so themselves.
 */
@Composable
fun CastTvTheme(content: @Composable () -> Unit) {
    MaTvTheme(accent = TvAccent.Blue, content = content)
}
