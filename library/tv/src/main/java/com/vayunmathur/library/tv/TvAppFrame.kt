package com.vayunmathur.library.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.SelectableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/** One top-level destination in the rail. [icon] is an `IconXxx()` from `:library:ui`. */
class TvDestination(val label: String, val icon: @Composable () -> Unit)

/**
 * The TV app shell: a collapsed icon rail on the left that expands over the content with a
 * scrim while it holds focus. Moving focus onto an item selects it (the Google TV pattern),
 * CENTER jumps into the content, and BACK from the content returns to the rail so the next
 * BACK exits instead of the app vanishing mid-browse.
 *
 * @param backReturnsToRail pass `false` when this frame is not the top of the back stack.
 */
@Composable
fun TvAppFrame(
    destinations: List<TvDestination>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    brand: (@Composable () -> Unit)? = null,
    backReturnsToRail: Boolean = true,
    content: @Composable () -> Unit,
) {
    var railFocused by remember { mutableStateOf(false) }
    val requesters = remember(destinations.size) { List(destinations.size) { FocusRequester() } }
    val selectedRequester = requesters.getOrElse(selected) { FocusRequester.Default }
    val focusManager = LocalFocusManager.current
    val railWidth by animateDpAsState(if (railFocused) TvDimens.RailExpanded else TvDimens.RailCollapsed)
    val scrimAlpha by animateFloatAsState(if (railFocused) 1f else 0f)
    val background = MaterialTheme.colorScheme.background

    // Registered before content so screen-level BackHandlers (closing a panel, clearing search) win.
    BackHandler(enabled = backReturnsToRail && !railFocused) {
        runCatching { selectedRequester.requestFocus() }
    }
    Box(modifier.fillMaxSize().background(background)) {
        Box(Modifier.fillMaxSize().padding(start = TvDimens.RailCollapsed)) {
            CompositionLocalProvider(LocalTvContentStart provides TvDimens.InsideFrameStart) {
                content()
            }
        }
        Box(
            Modifier
                .fillMaxHeight()
                .width(TvDimens.RailExpanded + 360.dp)
                .graphicsLayer { alpha = scrimAlpha }
                .background(
                    Brush.horizontalGradient(
                        0f to background,
                        0.45f to background.copy(alpha = 0.92f),
                        1f to Color.Transparent,
                    ),
                ),
        )
        Column(
            Modifier
                .fillMaxHeight()
                .width(railWidth)
                .onFocusChanged { railFocused = it.hasFocus }
                .focusRestorer(selectedRequester)
                .focusGroup()
                .padding(horizontal = 16.dp, vertical = TvDimens.SafeVertical),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.height(56.dp).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.CenterStart) {
                if (brand != null) {
                    Box(Modifier.wrapContentWidth(Alignment.Start, unbounded = true)) { brand() }
                }
            }
            Spacer(Modifier.weight(1f))
            destinations.forEachIndexed { index, destination ->
                RailItem(
                    destination = destination,
                    selected = index == selected,
                    expanded = railFocused,
                    onFocused = { if (index != selected) onSelect(index) },
                    onClick = {
                        onSelect(index)
                        focusManager.moveFocus(FocusDirection.Right)
                    },
                    modifier = Modifier.focusRequester(requesters[index]),
                )
            }
            Spacer(Modifier.weight(1.4f))
        }
    }
}

@Composable
private fun RailItem(
    destination: TvDestination,
    selected: Boolean,
    expanded: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        selected = selected,
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = SelectableSurfaceDefaults.shape(CircleShape),
        scale = SelectableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors = SelectableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = scheme.onSurfaceVariant,
            selectedContainerColor = scheme.primary.copy(alpha = 0.16f),
            selectedContentColor = scheme.primary,
            focusedContainerColor = scheme.inverseSurface,
            focusedContentColor = scheme.inverseOnSurface,
            focusedSelectedContainerColor = scheme.inverseSurface,
            focusedSelectedContentColor = scheme.inverseOnSurface,
        ),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                TvIcon(destination.icon)
            }
            if (expanded) {
                Text(
                    text = destination.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
        }
    }
}
