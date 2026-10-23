package com.vayunmathur.securemail.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.vayunmathur.library.ui.Messenger

/**
 * Shows a one-shot message on [messenger], then calls [onConsumed] so the
 * owner clears it — recompositions must not re-fire the snackbar.
 *
 * Screens must call this INSTEAD of `state.message?.let { messenger.show(it) }`:
 * the bare pattern re-shows on every recomposition because nothing consumes
 * the message (this caused the "identity claimed" snackbar to loop forever).
 */
@Composable
fun ConsumeMessage(
    message: String?,
    messenger: Messenger,
    onConsumed: () -> Unit,
) {
    LaunchedEffect(message) {
        if (message != null) {
            messenger.show(message)
            onConsumed()
        }
    }
}
