package com.vayunmathur.youpipe.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.youpipe.R

/**
 * D-pad audio-language list: one focusable row per language, display name with code
 * fallback. Replaces the phone dropdown in `ui/VideoPlayerTopControls.kt`.
 *
 * Entries mirror the phone's `languageEntries` (code to displayName-or-code), and the
 * caller's best-opus-per-language rule is untouched — this sheet only picks the code.
 */
@Composable
fun TvLanguageSheet(
    entries: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvOptionSheet(title = stringResource(R.string.language), onDismiss = onDismiss, modifier = modifier) {
        items(entries, key = { it.first }) { (code, display) ->
            Surface(onClick = { onSelect(code) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(
                    Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = display, style = MaterialTheme.typography.titleMedium)
                    if (code == selected) IconCheck()
                }
            }
        }
    }
}
