package com.vayunmathur.cast.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.cast.tv.R
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvDimens
import com.vayunmathur.library.tv.TvIcon
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.ui.IconCast
import com.vayunmathur.library.ui.IconDialpad
import com.vayunmathur.library.ui.IconLock
import com.vayunmathur.library.ui.IconTv
import com.vayunmathur.library.ui.SpecialAccess

/**
 * The ambient idle screen: what the TV shows while it waits for a phone.
 *
 * Calm rather than empty. The device name is the headline (it is what the phone's device list
 * shows, so matching it is the whole task), a cast glyph with an accent glow anchors the middle,
 * and three step cards say how to cast. The footer states the privacy facts the product is built
 * on - offline, E2EE, no account - plus the network name/IP when the receiver could read it.
 *
 * Paints its own background: the theme deliberately paints nothing, because the same theme wraps
 * an overlay above a decoder surface that must stay transparent.
 */
@Composable
fun ReadyScreen(deviceName: String, networkLabel: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.background)
            .padding(
                start = TvDimens.SafeHorizontal,
                end = TvDimens.SafeHorizontal,
                top = TvDimens.SafeVertical,
                bottom = TvDimens.SafeVertical,
            ),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(top = 24.dp),
        ) {
            CastGlyph()
            Text(
                text = deviceName,
                style = MaterialTheme.typography.displayMedium,
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.tv_ready_title),
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        HowToSteps(deviceName = deviceName)

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AutoOpenHintCard()
            Text(
                text = stringResource(R.string.tv_privacy_facts),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (networkLabel.isNotBlank()) {
                Text(
                    text = stringResource(R.string.tv_network_line, networkLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * The cast glyph with a soft accent glow: the visual anchor of the idle screen, echoing the
 * phone app's cast mark without any of its touch chrome.
 */
@Composable
private fun CastGlyph() {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(120.dp)
            .shadow(32.dp, CircleShape, ambientColor = scheme.primary, spotColor = scheme.primary)
            .background(scheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            TvIcon { IconCast() }
        }
    }
}

/** The three "how to cast" steps, as a row of cards readable from across the room. */
@Composable
private fun HowToSteps(deviceName: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
    ) {
        StepCard(
            number = "1",
            title = stringResource(R.string.tv_howto_step1_title),
            body = stringResource(R.string.tv_howto_step1_body),
            icon = { IconTv() },
            modifier = Modifier.weight(1f),
        )
        StepCard(
            number = "2",
            title = stringResource(R.string.tv_howto_step2_title, deviceName),
            body = stringResource(R.string.tv_howto_step2_body),
            icon = { IconCast() },
            modifier = Modifier.weight(1f),
        )
        StepCard(
            number = "3",
            title = stringResource(R.string.tv_howto_step3_title),
            body = stringResource(R.string.tv_howto_step3_body),
            icon = { IconDialpad() },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StepCard(
    number: String,
    title: String,
    body: String,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .background(scheme.surface, MaterialTheme.shapes.medium)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(scheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    TvIcon(icon)
                }
            }
            Text(
                text = number,
                style = MaterialTheme.typography.headlineSmall,
                color = scheme.primary,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = scheme.onSurface,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
    }
}

/**
 * The one-tap grant for auto-open, styled as a dismissible-looking hint card rather than a
 * blocking wall: the TV has two things to ask for at most, and a permissions gate would sit
 * between a user and a screen whose job is to wait.
 *
 * Reprobed on every resume so a grant made in Settings clears this without reopening the app.
 * Nothing here is focusable until the grant button appears, so initial focus goes there.
 */
@Composable
private fun AutoOpenHintCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var canAutoOpen by remember { mutableStateOf(SpecialAccess.hasFullScreenIntent(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canAutoOpen = SpecialAccess.hasFullScreenIntent(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (canAutoOpen) return
    val focusRequester = remember { FocusRequester() }
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            TvIcon { IconLock() }
        }
        Text(
            text = stringResource(R.string.tv_auto_open_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false),
        )
        TvButton(
            text = stringResource(R.string.tv_auto_open_grant),
            onClick = { SpecialAccess.requestFullScreenIntent(context) },
            primary = true,
            modifier = Modifier.focusRequester(focusRequester),
        )
    }
    TvInitialFocus(focusRequester)
}
