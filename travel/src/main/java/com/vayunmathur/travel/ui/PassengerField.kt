package com.vayunmathur.travel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text

/**
 * Compact passenger field for the dropdown row: shows a person icon + total
 * count, opening the Google-Flights-style passenger popup ([PassengerDialog]).
 */
@Composable
fun PassengerField(
    mix: PassengerMix,
    onMix: (PassengerMix) -> Unit,
    modifier: Modifier = Modifier,
) {
    var show by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(
            onClick = { show = true },
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconPerson()
                Text("${mix.total}", style = MaterialTheme.typography.labelLarge)
                Text("▾", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    if (show) {
        PassengerDialog(
            mix = mix,
            onDone = {
                onMix(it)
                show = false
            },
            onCancel = { show = false },
        )
    }
}
