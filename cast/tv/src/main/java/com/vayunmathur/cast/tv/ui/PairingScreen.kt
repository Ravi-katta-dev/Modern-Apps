package com.vayunmathur.cast.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.cast.tv.R
import com.vayunmathur.library.tv.TvDimens

/**
 * The pairing code, as huge individual digit tiles.
 *
 * The one thing on this screen the user reads from across a room and copies exactly, so each
 * digit gets its own accent-bordered box in `displayLarge` with a monospaced feel - chunks and
 * spacing carry over from the old layout, which grouped the same way for the same reason. Above
 * it, who is asking; below it, how many tries are left, or the notice that the code was replaced
 * after three wrong attempts.
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
        verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.tv_pair_from, senderName),
            style = MaterialTheme.typography.headlineSmall,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.tv_pair_title),
            style = MaterialTheme.typography.headlineMedium,
            color = scheme.onSurface,
            textAlign = TextAlign.Center,
        )
        DigitTiles(code)
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

@Composable
private fun DigitTiles(code: String) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
    ) {
        code.chunked(3).forEachIndexed { chunkIndex, chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                chunk.forEach { digit ->
                    Box(
                        modifier = Modifier
                            .size(width = 112.dp, height = 148.dp)
                            .background(scheme.surface, shape)
                            .border(3.dp, scheme.primary, shape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = digit.toString(),
                            style = MaterialTheme.typography.displayLarge.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = scheme.onSurface,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            // A breath between the two halves of the code, matching the old "123  456" grouping.
            if (chunkIndex == 0 && code.length > 3) {
                Box(Modifier.size(16.dp))
            }
        }
    }
}
