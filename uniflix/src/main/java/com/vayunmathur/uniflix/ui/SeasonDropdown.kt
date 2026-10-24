package com.vayunmathur.uniflix.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.uniflix.domain.SeasonSummary

/**
 * Season picker: a dropdown button showing the current season, replacing the
 * row of numbered buttons that overflowed on long-running shows.
 */
@Composable
fun SeasonDropdown(
    seasons: List<SeasonSummary>,
    selectedSeason: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = seasons.filter { it.seasonNumber > 0 }
    val current = options.firstOrNull { it.seasonNumber == selectedSeason } ?: options.firstOrNull()
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(current?.let { "Season ${it.seasonNumber} · ${it.episodeCount} episodes" } ?: "Season")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { season ->
                DropdownMenuItem(
                    text = { Text("Season ${season.seasonNumber} · ${season.episodeCount} episodes") },
                    onClick = {
                        expanded = false
                        onSelect(season.seasonNumber)
                    },
                )
            }
        }
    }
}
