package com.vayunmathur.library.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ColorScheme
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.compose.material3.LocalContentColor as M3LocalContentColor

/** One accent per app, so each TV app is recognisable at a glance from across the room. */
object TvAccent {
    val Red = Color(0xFFFF5B52)
    val Teal = Color(0xFF41D6A8)
    val Amber = Color(0xFFFFB84D)
    val Blue = Color(0xFF7DB4FF)
}

/** Ten-foot spacing. Horizontal/vertical safe values keep content inside TV overscan. */
object TvDimens {
    val SafeHorizontal: Dp = 48.dp
    val SafeVertical: Dp = 28.dp
    val RailCollapsed: Dp = 88.dp
    val RailExpanded: Dp = 264.dp
    val ItemGap: Dp = 20.dp
    val ShelfGap: Dp = 32.dp
    val InsideFrameStart: Dp = 24.dp
}

/** Start inset for screen content: smaller inside [TvAppFrame] because the rail already insets it. */
internal val LocalTvContentStart = compositionLocalOf { TvDimens.SafeHorizontal }

/**
 * Always-dark TV theme keyed off one accent. Focused surfaces flip to near-white with dark
 * content (the Google TV convention), so focus is legible on any backdrop.
 */
@Composable
fun MaTvTheme(accent: Color, content: @Composable () -> Unit) {
    val scheme = remember(accent) { tvColorScheme(accent) }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalContentColor provides scheme.onSurface,
            M3LocalContentColor provides scheme.onSurface,
            content = content,
        )
    }
}

/**
 * Bridges tv-material's content colour into Material 3's, which `:library:ui` icons read.
 * Without this an `IconXxx()` inside a focused tv surface keeps the unfocused colour.
 */
@Composable
fun TvIcon(content: @Composable () -> Unit) {
    CompositionLocalProvider(M3LocalContentColor provides LocalContentColor.current, content = content)
}

private val Background = Color(0xFF0B0C10)
private val Surface = Color(0xFF16181D)
private val SurfaceVariant = Color(0xFF23262D)
private val OnSurface = Color(0xFFECEDF0)
private val OnSurfaceVariant = Color(0xFFA6AAB3)

private fun tvColorScheme(accent: Color): ColorScheme {
    val onAccent = if (accent.luminance() > 0.4f) Background else Color.White
    return darkColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = lerp(accent, Background, 0.7f),
        onPrimaryContainer = lerp(accent, Color.White, 0.6f),
        secondary = lerp(accent, Color.White, 0.35f),
        onSecondary = Background,
        secondaryContainer = lerp(accent, SurfaceVariant, 0.8f),
        onSecondaryContainer = OnSurface,
        tertiary = lerp(accent, Color.White, 0.5f),
        background = Background,
        onBackground = OnSurface,
        surface = Surface,
        onSurface = OnSurface,
        surfaceVariant = SurfaceVariant,
        onSurfaceVariant = OnSurfaceVariant,
        inverseSurface = OnSurface,
        inverseOnSurface = Background,
        border = lerp(accent, Color.White, 0.25f),
        borderVariant = SurfaceVariant,
        error = Color(0xFFFF8A80),
        onError = Background,
        errorContainer = Color(0xFF5C1F1B),
        onErrorContainer = Color(0xFFFFDAD6),
        scrim = Color.Black,
    )
}
