package com.vayunmathur.cast.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.cast.tv.R
import com.vayunmathur.cast.tv.platform.ReceiverFailure
import com.vayunmathur.cast.tv.platform.ReceiverPhase
import com.vayunmathur.cast.tv.platform.ReceiverUiState
import com.vayunmathur.library.ui.SpecialAccess
import androidx.tv.material3.Button

/**
 * The idle and pairing screen.
 *
 * **On `androidx.tv`, and this paragraph used to argue the opposite.** The old reasoning was that
 * nothing here is focusable - no list to move a D-pad through, no button to press - so Material 3's
 * missing focus handling never came up, and the repo's rule that Material arrives only through
 * `:library:ui` could stay intact. That held for exactly as long as this app had no controls. It now
 * has a transport overlay driven from the television's remote, and building *that* on touch-first
 * components while this screen stayed on a third design system would leave one app with two.
 *
 * So the whole of `cast:tv` is on `androidx.tv`: the same Material design language, with focus as a
 * first-class concern rather than an afterthought. `:library:ui` remains on the compile classpath
 * because the app convention plugin puts it there, and R8 strips what nothing references.
 *
 * The `AppScaffold` went with it, and is not replaced. A title bar reading "Cast TV" above a screen
 * whose entire job is to show one sentence was furniture; the ten-foot rule is that everything on
 * screen should be worth reading from a sofa.
 *
 * The background is painted here rather than in `CastTvTheme`, because the other thing that theme wraps
 * is an overlay above a decoder surface and must stay transparent.
 *
 * Sizes are deliberately large: this is read from across a room, and a pair code the user has to walk
 * up to the TV to make out is a pair code that makes the feature feel broken.
 */
@Composable
fun ReceiverContent(state: ReceiverUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.localNetworkBlocked) {
            Headline(stringResource(R.string.tv_local_network_blocked), isError = true)
            Detail(stringResource(R.string.tv_local_network_blocked_hint))
            return@Column
        }
        when (val phase = state.phase) {
            ReceiverPhase.Starting -> Headline(stringResource(R.string.tv_starting))

            ReceiverPhase.Advertising -> {
                Headline(stringResource(R.string.tv_ready_title))
                Detail(
                    stringResource(
                        R.string.tv_ready_hint,
                        state.deviceName.ifBlank { stringResource(R.string.tv_name_unknown) },
                    ),
                )
                AutoOpenPrompt()
            }

            is ReceiverPhase.Pairing -> {
                Detail(stringResource(R.string.tv_pair_from, phase.senderName))
                Headline(stringResource(R.string.tv_pair_title))
                // The code itself, as large as the type scale goes: it is the one thing on this
                // screen the user has to read from across a room and copy exactly.
                Text(
                    text = phase.code.chunked(3).joinToString("  "),
                    style = MaterialTheme.typography.displayLarge,
                    textAlign = TextAlign.Center,
                )
                if (phase.codeChanged) {
                    Detail(stringResource(R.string.tv_pair_new_code), isError = true)
                } else {
                    Detail(
                        pluralStringResource(
                            R.plurals.tv_pair_attempts,
                            phase.attemptsLeft,
                            phase.attemptsLeft,
                        ),
                    )
                }
            }

            is ReceiverPhase.Connected ->
                Headline(stringResource(R.string.tv_paired, phase.senderName))

            // The mirror has its own full-screen Activity, so this is only ever seen for the
            // moment between the stream starting and that Activity coming up.
            is ReceiverPhase.Mirroring ->
                Headline(stringResource(R.string.tv_mirroring, phase.sourceName))

            is ReceiverPhase.Failed -> Headline(
                stringResource(
                    when (phase.reason) {
                        ReceiverFailure.NoDecoder -> R.string.tv_no_decoder
                        ReceiverFailure.NoAudioDecoder -> R.string.tv_no_audio_decoder
                        ReceiverFailure.MissingCodecConfig ->
                            R.string.tv_missing_codec_config
                        ReceiverFailure.Handshake -> R.string.tv_stream_failed
                        ReceiverFailure.StreamEnded -> R.string.tv_stream_failed
                    },
                ),
                isError = true,
            )
        }
    }
}

@Composable
private fun Headline(text: String, isError: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineLarge,
        textAlign = TextAlign.Center,
        color = if (isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    )
}

@Composable
private fun Detail(text: String, isError: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        color = if (isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

/**
 * The one-tap grant for auto-open, shown on the idle screen until full-screen-intent access is
 * held.
 *
 * A `Button` rather than the phone-style permissions gate: the TV has two things to ask for
 * at most and a blocking wall would sit between a user and a screen whose whole job is one
 * sentence. Reprobed on every resume so a grant made in Settings clears this without reopening
 * the app.
 */
@Composable
private fun AutoOpenPrompt() {
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
    Detail(stringResource(R.string.tv_auto_open_hint))
    Button(onClick = { SpecialAccess.requestFullScreenIntent(context) }) {
        Text(stringResource(R.string.tv_auto_open_grant))
    }
}
