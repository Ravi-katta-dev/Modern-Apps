package com.vayunmathur.youpipe.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.ui.videoRowState
import com.vayunmathur.youpipe.util.decodeHtml
import com.vayunmathur.youpipe.util.YouPipeViewModel

/**
 * TV subscriptions: groups + all-feed entry + channel list, all D-pad focusable.
 *
 * Mirrors the phone `SubscriptionsPage` binding (subscriptions, categoryNames, fetchProgress)
 * with no top bar — the tab strip is the chrome. Each group row and channel row is a
 * focusable `Surface`; the edit/add buttons are separate focus targets beside their rows
 * rather than trailing icons, because a D-pad cannot land "inside" a row.
 */
@Composable
fun TvSubscriptionsPage(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
) {
    val subscriptions by ypvm.subscriptions.collectAsState()
    val categories by ypvm.categoryNames.collectAsState()
    val fetchProgress by ypvm.fetchProgress.collectAsState()
    val context = LocalContext.current
    val firstFocus = remember { FocusRequester() }

    TvInitialFocus(firstFocus)
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (fetchProgress in 0f..1f) {
            item(key = "fetch-progress") {
                TvProgressRow(stringResource(R.string.loading_recent_subscriptions), fetchProgress)
            }
        }
        item(key = "groups-header") {
            Text(text = stringResource(R.string.label_groups), style = MaterialTheme.typography.headlineSmall)
        }
        item(key = "all-subscriptions") {
            Surface(
                onClick = { backStack.add(TvRoute.SubscriptionFeed(null)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(firstFocus),
            ) {
                Text(
                    text = stringResource(R.string.label_all_subscriptions),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        items(categories, key = { "cat-$it" }) { category ->
            Surface(
                onClick = { backStack.add(TvRoute.SubscriptionFeed(category)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(text = category, style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { backStack.add(TvRoute.CreateSubscriptionCategory(category)) }) {
                        IconEdit()
                    }
                }
            }
        }
        item(key = "new-group") {
            Button(onClick = { backStack.add(TvRoute.CreateSubscriptionCategory(null)) }) {
                IconAdd()
            }
        }
        item(key = "channels-header") {
            Text(text = stringResource(R.string.label_channels), style = MaterialTheme.typography.headlineSmall)
        }
        items(subscriptions, key = { it.id }) { sub ->
            Surface(
                onClick = { backStack.add(TvRoute.Channel(sub.channelID)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                androidx.compose.foundation.layout.Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    if (sub.avatarURL.isNotEmpty()) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(sub.avatarURL)
                                .memoryCacheKey("sub-avatar-${sub.id}")
                                .build(),
                            contentDescription = null,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                    Text(
                        text = sub.name.decodeHtml(),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

/** Fetch progress row: label + determinate indicator, non-focusable (status, not a target). */
@Composable
private fun TvProgressRow(label: String, progress: Float) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        CircularProgressIndicator(progress = { progress })
    }
}
