package com.vayunmathur.travel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconRemove
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

/** A +/- stepper for a small integer count (passengers). */
@Composable
fun CountStepper(
    label: String,
    count: Int,
    onCount: (Int) -> Unit,
    modifier: Modifier = Modifier,
    min: Int = 1,
    max: Int = 9,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onCount((count - 1).coerceAtLeast(min)) }, enabled = count > min) {
                IconRemove()
            }
            Text("$count", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 8.dp))
            IconButton(onClick = { onCount((count + 1).coerceAtMost(max)) }, enabled = count < max) {
                IconAdd()
            }
        }
    }
}
