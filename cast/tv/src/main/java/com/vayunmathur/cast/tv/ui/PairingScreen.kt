package com.vayunmathur.cast.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.cast.tv.R
import com.vayunmathur.library.tv.TvDimens

/**
 * The pairing code, very large and otherwise undecorated.
 *
 * The one thing on this screen the user reads from across a room and copies exactly, so it is
 * huge monospaced type with the same "123 456" grouping as ever, sitting in one card in the
 * phone app's card language: who is asking above it, tries left (or the replaced-code notice)
 * below it. No tiles, no borders, no glow.
 *
 * Nothing is focusable: there is no input on this screen (the typing happens on the phone), so
 * a focus target would be a thing the D-pad could reach that does nothing.
 */
@Composable
fun PairingScreen(
    senderName: String,
    code: String,
    attemptsLeft: Int,
    codeChanged: Boolean,
    modifier: Modifier = Modifier,
) {
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
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(fraction = CODE_CARD_WIDTH_FRACTION)
                .background(scheme.surface, MaterialTheme.shapes.medium)
                .padding(horizontal = 32.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.tv_pair_from, senderName),
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.tv_pair_title),
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Code(code)
            if (codeChanged) {
                Text(
                    text = stringResource(R.string.tv_pair_new_code),
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.error,
                    textAlign = TextAlign.Center,
                )
            } else {
                Text(
                    text = pluralStringResource(R.plurals.tv_pair_attempts, attemptsLeft, attemptsLeft),
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** The six digits, huge and monospaced, grouped the way they are read out. */
@Composable
private fun Code(code: String, modifier: Modifier = Modifier) {
    Text(
        text = code.chunked(CODE_CHUNK).joinToString(separator = CODE_GAP),
        style = MaterialTheme.typography.displayLarge.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = CODE_SIZE_SP.sp,
            letterSpacing = CODE_LETTER_SPACING_SP.sp,
        ),
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = modifier,
    )
}

private const val CODE_CHUNK = 3
private const val CODE_GAP = "  "
private const val CODE_SIZE_SP = 96
private const val CODE_LETTER_SPACING_SP = 4
private const val CODE_CARD_WIDTH_FRACTION = 0.62f
