package com.vayunmathur.library.tv

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/**
 * Request focus once composed, so the D-pad always has somewhere to start. Re-runs when [key]
 * changes (e.g. content arriving after a load).
 */
@Composable
fun TvInitialFocus(requester: FocusRequester, key: Any? = Unit) {
    LaunchedEffect(requester, key) {
        // One frame late: requesting during the first layout races the focus system and drops.
        delay(FRAME_MS)
        runCatching { requester.requestFocus() }
    }
}

/** Centered empty/error state. [action] is usually one [TvButton] so the D-pad has a target. */
@Composable
fun TvEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    icon: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    Box(modifier.fillMaxSize().padding(TvDimens.SafeHorizontal), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 640.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) {
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    TvIcon(icon)
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            if (body != null) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (action != null) {
                Box(Modifier.padding(top = 12.dp)) { action() }
            }
        }
    }
}

/**
 * Centered spinner. Focusable so the D-pad is not left dead while content loads; pass a
 * [focusRequester] via [modifier] if the screen should start here.
 */
@Composable
fun TvLoading(modifier: Modifier = Modifier, label: String? = null) {
    Box(modifier.fillMaxSize().focusable(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val FRAME_MS = 16L
