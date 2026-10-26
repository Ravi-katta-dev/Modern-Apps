package com.vayunmathur.library.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.SelectableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/** One top-level destination in the rail. [icon] is an `IconXxx()` from `:library:ui`. */
class TvDestination(val label: String, val icon: @Composable () -> Unit)

/**
 * The TV app shell: a slim fixed rail (icon over a short label, like the phone's bottom bar
 * turned sideways) and the selected destination's content beside it. The rail never expands
 * or overlays content. Focusing an item selects it, CENTER jumps into the content, and BACK
 * from the content returns to the rail so the next BACK exits.
 *
 * @param backReturnsToRail pass `false` when this frame is not the top of the back stack.
 */
@Composable
fun TvAppFrame(
    destinations: List<TvDestination>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    backReturnsToRail: Boolean = true,
    content: @Composable () -> Unit,
) {
    var railFocused by remember { mutableStateOf(false) }
    val requesters = remember(destinations.size) { List(destinations.size) { FocusRequester() } }
    val selectedRequester = requesters.getOrElse(selected) { FocusRequester.Default }
    val focusManager = LocalFocusManager.current

    // Registered before content so screen-level BackHandlers (closing a panel, clearing search) win.
    BackHandler(enabled = backReturnsToRail && !railFocused) {
        runCatching { selectedRequester.requestFocus() }
    }
    Row(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier
                .fillMaxHeight()
                .width(TvDimens.Rail)
                .onFocusChanged { railFocused = it.hasFocus }
                .focusRestorer(selectedRequester)
                .focusGroup()
                .padding(horizontal = 8.dp, vertical = TvDimens.SafeVertical),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            destinations.forEachIndexed { index, destination ->
                RailItem(
                    destination = destination,
                    selected = index == selected,
                    onFocused = { if (index != selected) onSelect(index) },
                    onClick = {
                        onSelect(index)
                        focusManager.moveFocus(FocusDirection.Right)
                    },
                    modifier = Modifier.focusRequester(requesters[index]),
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            CompositionLocalProvider(LocalTvContentStart provides TvDimens.InsideFrameStart) {
                content()
            }
        }
    }
}

@Composable
private fun RailItem(
    destination: TvDestination,
    selected: Boolean,
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
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = SelectableSurfaceDefaults.shape(MaterialTheme.shapes.medium),
        scale = SelectableSurfaceDefaults.scale(focusedScale = 1.05f),
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
        Column(
            Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                TvIcon(destination.icon)
            }
            Text(
                text = destination.label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
