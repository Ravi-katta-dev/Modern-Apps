package com.vayunmathur.youpipe.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.util.YouPipeViewModel

/**
 * TV create-playlist: name field (explicit IME only) + focused create row.
 *
 * Mirrors the phone `CreatePlaylist` validation: non-blank and no duplicate name.
 */
@Composable
fun TvCreatePlaylistPage(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
) {
    val allPlaylists by ypvm.playlists.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    val createFocus = remember { FocusRequester() }

    val canCreate = name.isNotBlank() && allPlaylists.none { it.name == name.trim() }

    TvInitialFocus(createFocus)
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = stringResource(R.string.title_create_playlist), style = MaterialTheme.typography.headlineSmall)
        BasicTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            textStyle = MaterialTheme.typography.titleMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    ypvm.playlistOps.createPlaylist(name.trim())
                    backStack.pop()
                },
                enabled = canCreate,
                modifier = Modifier.focusRequester(createFocus),
            ) {
                Text(stringResource(R.string.new_playlist))
            }
            Button(onClick = { backStack.pop() }) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    }
}
