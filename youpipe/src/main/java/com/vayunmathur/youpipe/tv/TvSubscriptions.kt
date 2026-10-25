package com.vayunmathur.youpipe.tv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvChip
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvMediaCard
import com.vayunmathur.library.tv.TvProgressStrip
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.tv.TvShelf
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.util.YouPipeViewModel
import com.vayunmathur.youpipe.util.decodeHtml

/**
 * TV subscriptions: a shelf of channels (circular avatars), category chips, and a latest
 * videos shelf — mirroring the phone `SubscriptionsPage` bindings (subscriptions,
 * categoryNames, fetchProgress) with no top bar; the rail is the chrome.
 */
@Composable
fun TvSubscriptionsPage(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
) {
    val subscriptions by ypvm.subscriptions.collectAsState()
    val categories by ypvm.categoryNames.collectAsState()
    val fetchProgress by ypvm.fetchProgress.collectAsState()
    val history by ypvm.historyVideos.collectAsState()
    val historyById = remember(history) { history.associate { it.id to it.progress } }

    var selectedCategory by remember { mutableStateOf<String?>(null) }
    val latestSubs by remember(selectedCategory) { ypvm.subscriptionVideosFor(selectedCategory) }
        .collectAsState(initial = emptyList())
    val latestRows = rememberTvVideoRows(latestSubs, ypvm, historyById)
    val firstFocus = remember { FocusRequester() }

    TvInitialFocus(firstFocus)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TvScreenHeader(
            title = stringResource(R.string.title_subscriptions),
            actions = {
                TvButton(
                    text = stringResource(R.string.title_create_subscription_category),
                    onClick = { backStack.add(TvRoute.CreateSubscriptionCategory(null)) },
                    icon = { IconAdd() },
                )
            },
        )
        if (fetchProgress in 0f..1f) {
            TvProgressStrip(fetchProgress, Modifier.padding(horizontal = 48.dp))
        }
        if (subscriptions.isEmpty() && categories.isEmpty()) {
            TvEmptyState(title = stringResource(R.string.empty_recommendations))
            return@Column
        }
        TvShelf(title = stringResource(R.string.label_channels)) {
            items(subscriptions.size, key = { subscriptions[it].id }) { index ->
                val sub = subscriptions[index]
                TvChannelAvatarCard(
                    name = sub.name.decodeHtml(),
                    avatarURL = sub.avatarURL,
                    cacheKey = "sub-avatar-${sub.id}",
                    onClick = { backStack.add(TvRoute.Channel(sub.channelID)) },
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
        if (categories.isNotEmpty()) {
            Row(Modifier.padding(start = 48.dp, end = 48.dp, top = 8.dp)) {
                TvChip(
                    label = stringResource(R.string.label_all_subscriptions),
                    selected = selectedCategory == null,
                    onClick = { selectedCategory = null },
                )
                categories.forEach { category ->
                    TvChip(
                        label = category,
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            // Per-category edit affordance: D-pad reachable, beside the chips, never nested.
            Row(Modifier.padding(start = 48.dp, top = 8.dp)) {
                categories.forEach { category ->
                    TvButton(
                        text = category,
                        onClick = { backStack.add(TvRoute.CreateSubscriptionCategory(category)) },
                        icon = { IconEdit() },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
        }
        if (latestRows.isNotEmpty()) {
            TvShelf(
                title = selectedCategory ?: stringResource(R.string.label_all_subscriptions),
            ) {
                items(latestRows.size, key = { latestRows[it].row.videoID }) { index ->
                    val item = latestRows[index]
                    TvVideoCard(
                        item = item,
                        onOpen = { backStack.add(TvRoute.Video(item.row.videoID)) },
                    )
                }
            }
        }
    }
}

/** One channel: circular avatar over the channel name. */
@Composable
private fun TvChannelAvatarCard(
    name: String,
    avatarURL: String,
    cacheKey: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    TvMediaCard(
        onClick = onClick,
        modifier = modifier,
        width = 160.dp,
        aspectRatio = 1f,
        title = name,
        image = {
            if (avatarURL.isNotEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(avatarURL)
                        .memoryCacheKey(cacheKey)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            }
        },
    )
}

/** Avatar size used by the channel header on [TvChannelPage]. */
internal val TvAvatarSize = 96.dp

/** Modifier helper for a circular avatar image. */
internal fun Modifier.tvCircularAvatar(): Modifier = this.clip(CircleShape).size(TvAvatarSize)
