package com.vayunmathur.youpipe.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import com.vayunmathur.library.tv.TvAspect
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvChip
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvGrid
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvMediaCard
import com.vayunmathur.library.tv.TvProgressStrip
import com.vayunmathur.library.tv.tvFullWidth
import com.vayunmathur.library.tv.tvGridHeader
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.util.YouPipeViewModel
import com.vayunmathur.youpipe.util.decodeHtml

/**
 * TV subscriptions: one scrolling 4-column grid mirroring the phone `SubscriptionsPage`.
 *
 * Category chips span the full width (All + each group, filtering the latest grid below),
 * then channels as compact avatar+name cards, then latest videos as 16:9 cards.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    val channelsTitle = stringResource(R.string.tv_section_channels)
    val allSubsTitle = stringResource(R.string.label_all_subscriptions)
    val createCategoryLabel = stringResource(R.string.title_create_subscription_category)
    val updateLabel = stringResource(R.string.action_update)

    if (subscriptions.isEmpty() && categories.isEmpty()) {
        TvEmptyState(title = stringResource(R.string.empty_recommendations))
        return
    }

    TvInitialFocus(firstFocus)
    TvGrid(columns = TV_GRID_COLUMNS, modifier = Modifier.fillMaxSize()) {
        if (fetchProgress in 0f..1f) {
            tvFullWidth(key = "fetch-progress") {
                TvProgressStrip(fetchProgress, Modifier.fillMaxWidth().padding(bottom = 8.dp))
            }
        }
        tvFullWidth(key = "category-chips") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TvChip(
                    label = stringResource(R.string.label_all_subscriptions),
                    selected = selectedCategory == null,
                    onClick = { selectedCategory = null },
                    modifier = Modifier.focusRequester(firstFocus),
                )
                categories.forEach { category ->
                    TvChip(
                        label = category,
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                    )
                }
                TvButton(
                    text = createCategoryLabel,
                    onClick = { backStack.add(TvRoute.CreateSubscriptionCategory(null)) },
                    icon = { IconAdd() },
                )
                if (selectedCategory != null) {
                    TvButton(
                        text = updateLabel,
                        onClick = {
                            backStack.add(TvRoute.CreateSubscriptionCategory(selectedCategory))
                        },
                        icon = { IconEdit() },
                    )
                }
            }
        }
        tvGridHeader(
            key = "channels-header",
            title = channelsTitle,
            first = true,
        )
        items(subscriptions.size, key = { subscriptions[it].id }) { index ->
            val sub = subscriptions[index]
            TvChannelAvatarCard(
                name = sub.name.decodeHtml(),
                avatarURL = sub.avatarURL,
                cacheKey = "sub-avatar-${sub.id}",
                onClick = { backStack.add(TvRoute.Channel(sub.channelID)) },
            )
        }
        tvGridHeader(
            key = "latest-header",
            title = selectedCategory ?: allSubsTitle,
        )
        items(latestRows.size, key = { latestRows[it].row.videoID }) { index ->
            val item = latestRows[index]
            TvVideoCard(
                item = item,
                onOpen = { backStack.playVideo(item.row.videoID) },
            )
        }
    }
}

/** One channel: circular avatar over the channel name. Fills its grid cell. */
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
        aspectRatio = TvAspect.Square,
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
