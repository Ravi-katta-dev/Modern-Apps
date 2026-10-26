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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
import com.vayunmathur.library.ui.IconLock
import com.vayunmathur.library.ui.IconTv
import com.vayunmathur.library.ui.SpecialAccess

/**
 * The ambient idle screen: what the TV shows while it waits for a phone.
 *
 * Two columns that use the width, in the phone app's card language: on the left a card naming
 * this TV (the name the phone's device list shows, so matching it is the whole task) with its
 * ready status and network line, then the auto-open hint while it is still needed and the
 * privacy facts as a plain line; on the right one card with the three steps. Vertically
 * centred so the pair sits mid-screen. No glyph, no hero.
 *
 * Paints its own background: the theme deliberately paints nothing, because the same theme wraps
 * an overlay above a decoder surface that must stay transparent.
 */
@Composable
fun ReadyScreen(deviceName: String, networkLabel: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.background)
            .padding(
                start = TvDimens.SafeHorizontal,
                end = TvDimens.SafeHorizontal,
                top = TvDimens.SafeVertical,
                bottom = TvDimens.SafeVertical,
            ),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DeviceCard(deviceName = deviceName, networkLabel = networkLabel)
            AutoOpenHintCard()
            Text(
                text = stringResource(R.string.tv_privacy_facts),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        }
        HowToCard(deviceName = deviceName, modifier = Modifier.weight(1f))
    }
}

/** This TV: its name, its ready status, and the network line when the receiver could read it. */
@Composable
private fun DeviceCard(deviceName: String, networkLabel: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(scheme.surface, MaterialTheme.shapes.medium)
            .padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(scheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                TvIcon { IconTv() }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = deviceName,
                style = MaterialTheme.typography.headlineSmall,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.tv_ready_title),
                style = MaterialTheme.typography.titleMedium,
                color = scheme.primary,
            )
            if (networkLabel.isNotBlank()) {
                Text(
                    text = stringResource(R.string.tv_network_line, networkLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The three "how to cast" steps, as compact numbered rows in one card. */
@Composable
private fun HowToCard(deviceName: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(scheme.surface, MaterialTheme.shapes.medium)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Step(
            number = "1",
            title = stringResource(R.string.tv_howto_step1_title),
            body = stringResource(R.string.tv_howto_step1_body),
        )
        Step(
            number = "2",
            title = stringResource(R.string.tv_howto_step2_title, deviceName),
            body = stringResource(R.string.tv_howto_step2_body),
        )
        Step(
            number = "3",
            title = stringResource(R.string.tv_howto_step3_title),
            body = stringResource(R.string.tv_howto_step3_body),
        )
    }
}

@Composable
private fun Step(number: String, title: String, body: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(scheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number,
                style = MaterialTheme.typography.titleMedium,
                color = scheme.primary,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
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
