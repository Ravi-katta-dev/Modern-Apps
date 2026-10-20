package com.vayunmathur.youpipe.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.util.clearChannelPreference
import com.vayunmathur.youpipe.util.decodeHtml
import com.vayunmathur.youpipe.util.pinChannel
import com.vayunmathur.youpipe.util.removeInterest
import com.vayunmathur.youpipe.util.unmuteKeyword
import com.vayunmathur.youpipe.util.YouPipeViewModel

/**

 * TV interests: channels/topics/blocked/muted as focusable rows with pin/remove actions.

 *

 * Same `interestProfile`/`channelPreferences`/`keywordPreferences` bindings as the phone;

 * trailing buttons become stacked focus targets under each row (D-pad DOWN reaches them).

 */

@Composable

fun TvManageInterestsPage(

    backStack: NavBackStack<TvRoute>,

    ypvm: YouPipeViewModel,

) {

    val profile by ypvm.interestProfile.collectAsState()

    val channelPrefs by ypvm.channelPreferences.collectAsState()

    val keywordPrefs by ypvm.keywordPreferences.collectAsState()

    val firstFocus = remember { FocusRequester() }



    val blockedKeys = channelPrefs.filter { it.blocked }.map { it.channelKey }.toSet()

    val pinnedKeys = channelPrefs.filter { it.pinned }.map { it.channelKey }.toSet()

    val topChannels = remember(profile, blockedKeys) {

        profile.authorWeights.entries

            .filter { it.key.isNotBlank() && it.key !in blockedKeys }

            .sortedByDescending { it.value }

            .take(30)

            .map { it.key }

    }

    val topKeywords = remember(profile) {

        profile.keywordWeights.entries

            .sortedByDescending { it.value }

            .take(30)

            .map { it.key }

    }

    val blockedChannels = remember(channelPrefs) {

        channelPrefs.filter { it.blocked }.map { it.channelKey }

    }

    val mutedKeywords = remember(keywordPrefs) {

        keywordPrefs.filter { it.muted }.map { it.keyword }

    }



    TvInitialFocus(firstFocus)

    LazyColumn(

        Modifier

            .fillMaxSize()

            .padding(horizontal = 48.dp, vertical = 32.dp),

        verticalArrangement = Arrangement.spacedBy(8.dp),

    ) {

        item(key = "interests-title") {

            Text(text = stringResource(R.string.title_manage_interests), style = MaterialTheme.typography.headlineSmall)

        }

        item(key = "ch-header") { TvSectionHeader(stringResource(R.string.label_your_channels)) }

        if (topChannels.isEmpty()) {

            item(key = "ch-empty") { TvEmptyRow(stringResource(R.string.label_no_channels)) }

        }

        items(topChannels, key = { "ch-$it" }) { channelKey ->

            val pinned = channelKey in pinnedKeys

            TvInterestRow(

                label = channelKey.decodeHtml(),

                badge = if (pinned) stringResource(R.string.label_pinned) else null,

                primaryAction = if (pinned) {

                    stringResource(R.string.action_unpin)

                } else {

                    stringResource(R.string.action_pin)

                },

                onPrimary = {

                    if (pinned) ypvm.clearChannelPreference(channelKey) else ypvm.pinChannel(channelKey)

                },

                secondaryAction = stringResource(UiR.string.remove),

                onSecondary = { ypvm.removeInterest(channelKey = channelKey) },

            )

        }

        item(key = "kw-header") { TvSectionHeader(stringResource(R.string.label_your_topics)) }

        if (topKeywords.isEmpty()) {

            item(key = "kw-empty") { TvEmptyRow(stringResource(R.string.label_no_topics)) }

        }

        items(topKeywords, key = { "kw-$it" }) { keyword ->

            TvInterestRow(

                label = keyword,

                primaryAction = stringResource(UiR.string.remove),

                onPrimary = { ypvm.removeInterest(keyword = keyword) },

            )

        }

        item(key = "bl-header") { TvSectionHeader(stringResource(R.string.label_blocked_channels)) }

        if (blockedChannels.isEmpty()) {

            item(key = "bl-empty") { TvEmptyRow(stringResource(R.string.label_no_blocked_channels)) }

        }

        items(blockedChannels, key = { "bl-$it" }) { channelKey ->

            TvInterestRow(

                label = channelKey.decodeHtml(),

                primaryAction = stringResource(R.string.action_unblock),

                onPrimary = { ypvm.clearChannelPreference(channelKey) },

            )

        }

        item(key = "mk-header") { TvSectionHeader(stringResource(R.string.label_muted_keywords)) }

        if (mutedKeywords.isEmpty()) {

            item(key = "mk-empty") { TvEmptyRow(stringResource(R.string.label_no_muted_keywords)) }

        }

        items(mutedKeywords, key = { "mk-$it" }) { keyword ->

            TvInterestRow(

                label = keyword,

                primaryAction = stringResource(R.string.action_unmute),

                onPrimary = { ypvm.unmuteKeyword(keyword) },

            )

        }

    }

}



@Composable

private fun TvSectionHeader(text: String) {

    Text(

        text = text,

        style = MaterialTheme.typography.titleSmall,

        color = MaterialTheme.colorScheme.primary,

    )

}



@Composable

private fun TvEmptyRow(text: String) {

    Text(

        text = text,

        style = MaterialTheme.typography.bodyMedium,

        color = MaterialTheme.colorScheme.onSurfaceVariant,

    )

}



/**

 * One interest row: label + badge + stacked action buttons, each a focus target.

 * Actions stack under the label (not beside it) so DOWN always reaches them.

 */

@Composable

private fun TvInterestRow(

    label: String,

    primaryAction: String,

    onPrimary: () -> Unit,

    modifier: Modifier = Modifier,

    badge: String? = null,

    secondaryAction: String? = null,

    onSecondary: (() -> Unit)? = null,

) {

    Surface(onClick = onPrimary, modifier = modifier.fillMaxWidth()) {

        Column(Modifier.padding(16.dp)) {

            Text(text = label, style = MaterialTheme.typography.titleMedium)

            if (badge != null) {

                Text(

                    text = badge,

                    style = MaterialTheme.typography.bodySmall,

                    color = MaterialTheme.colorScheme.primary,

                )

            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {

                Button(onClick = onPrimary) { Text(primaryAction) }

                if (secondaryAction != null && onSecondary != null) {

                    Button(onClick = onSecondary) { Text(secondaryAction) }

                }

            }

        }

    }

}
