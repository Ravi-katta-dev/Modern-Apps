package com.vayunmathur.youpipe.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.ui.ChannelInfo
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.ui.videoRowState
import com.vayunmathur.youpipe.util.YouPipeViewModel

/**
 * TV search: a text field that opens only on explicit focus + IME action.
 *
 * The phone's `SearchBar` autofocuses and pops the IME the moment the tab composes — on a TV
 * that means the keyboard covers the feed before the user has pressed anything. Here the field
 * is an ordinary focusable row: focusing it does nothing, pressing CENTER moves focus *into*
 * the field (which opens the TV IME), and the IME's search action submits. Suggestions and
 * results are D-pad lists below; BACK from the field returns focus to the list, BACK again
 * leaves the screen.
 */
@Composable
fun TvSearchPage(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
) {
    val query by ypvm.searchQuery.collectAsState()
    val suggestions by ypvm.suggestions.collectAsState()
    val results by ypvm.searchResults.collectAsState()
    val history by ypvm.historyVideos.collectAsState()
    val deArrowEnabled by ypvm.deArrowEnabled.collectAsState()
    val deArrowCache by ypvm.deArrowCache.collectAsState()
    val progressById = remember(history) { history.associate { it.id to it.progress } }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var fieldText by remember(query) { mutableStateOf(query) }
    var fieldOpen by remember { mutableStateOf(false) }
    val entryFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }

    fun submit() {
        ypvm.setSearchQuery(fieldText)
        val watchID = ypvm.resolveWatchUrl()
        if (watchID != null) {
            backStack.add(TvRoute.Video(watchID))
        } else {
            ypvm.performSearch()
        }
        fieldOpen = false
        focusManager.clearFocus()
    }

    TvInitialFocus(entryFocus)
    Column(Modifier.fillMaxSize()) {
        // The entry row: focused by default, CENTER opens the field, never the IME directly.
        Surface(
            onClick = { fieldOpen = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp, vertical = 16.dp)
                .focusRequester(entryFocus),
        ) {
            Text(
                text = fieldText.ifBlank { stringResource(UiR.string.search) },
                style = MaterialTheme.typography.titleMedium,
                color = if (fieldText.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.padding(16.dp),
            )
        }

        if (fieldOpen) {
            // The actual text field: only composed after the explicit open, so the IME can
            // only ever appear as the result of a CENTER press. IME search action submits.
            androidx.compose.foundation.text.BasicTextField(
                value = fieldText,
                onValueChange = {
                    fieldText = it
                    ypvm.setSearchQuery(it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp)
                    .focusRequester(fieldFocus),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
            )
            LaunchedEffect(fieldFocus) {
                fieldFocus.requestFocus()
            }
        }

        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (results.isNotEmpty()) {
                items(results, key = {
                    when (it) {
                        is VideoInfo -> "v-${it.videoID}"
                        is ChannelInfo -> "c-${it.channelID}"
                        else -> it.hashCode()
                    }
                }) { item ->
                    when (item) {
                        is VideoInfo -> {
                            val deArrow = if (deArrowEnabled) deArrowCache[item.videoID] else null
                            val watched = progressById[item.videoID] ?: 0L
                            TvVideoCard(
                                row = videoRowState(
                                    context = context,
                                    videoInfo = item,
                                    showAuthor = true,
                                    percentWatched = if (item.duration > 0) {
                                        (watched.toDouble() / item.duration).toFloat()
                                    } else {
                                        0f
                                    },
                                    deArrowTitle = deArrow?.title,
                                    deArrowThumbnailURL = deArrow?.thumbnailUrl,
                                ),
                                onOpen = { backStack.add(TvRoute.Video(item.videoID)) },
                            )
                        }
                        is ChannelInfo -> {
                            Surface(
                                onClick = { backStack.add(TvRoute.Channel(item.channelID)) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(text = item.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        text = stringResource(
                                            R.string.subscribers_count,
                                            com.vayunmathur.youpipe.ui.countString(context, item.subscribers),
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                items(suggestions, key = { it }) { suggestion ->
                    Surface(
                        onClick = {
                            fieldText = suggestion
                            ypvm.setSearchQuery(suggestion)
                            val watchID = ypvm.resolveWatchUrl()
                            if (watchID != null) {
                                backStack.add(TvRoute.Video(watchID))
                            } else {
                                ypvm.performSearch()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = suggestion, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
    }
}
