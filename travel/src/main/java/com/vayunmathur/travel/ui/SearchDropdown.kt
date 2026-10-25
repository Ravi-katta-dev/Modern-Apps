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
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SelectableDropdownMenuItem
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text

/**
 * A compact pill-style dropdown used in the search-form dropdown row
 * (Google-Flights style): shows [display] with a caret, opening a
 * [DropdownMenu] of [options] on tap. The selected option renders selected.
 *
 * @param options value → label pairs shown in the menu.
 */
@Composable
fun SearchDropdown(
    display: String,
    options: List<Pair<String, String>>,
    selectedValue: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(
            onClick = { expanded = true },
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                leadingIcon?.invoke()
                Text(display, style = MaterialTheme.typography.labelLarge)
                Text("▾", style = MaterialTheme.typography.labelLarge)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, label) ->
                SelectableDropdownMenuItem(
                    selected = value == selectedValue,
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                    text = { Text(label) },
                )
            }
        }
    }
}
