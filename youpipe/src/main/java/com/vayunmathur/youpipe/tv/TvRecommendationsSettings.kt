package com.vayunmathur.youpipe.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.ui.presetLabel
import com.vayunmathur.youpipe.util.RecommendationPreset
import com.vayunmathur.youpipe.util.RecSource
import com.vayunmathur.youpipe.util.resetAlgorithm
import com.vayunmathur.youpipe.util.setDiscoveryFamiliar
import com.vayunmathur.youpipe.util.setFocusedDiverse
import com.vayunmathur.youpipe.util.setFreshEvergreen
import com.vayunmathur.youpipe.util.setHideLive
import com.vayunmathur.youpipe.util.setHidePaid
import com.vayunmathur.youpipe.util.setHideShorts
import com.vayunmathur.youpipe.util.setMaxDuration
import com.vayunmathur.youpipe.util.setMinDuration
import com.vayunmathur.youpipe.util.setPreset
import com.vayunmathur.youpipe.util.toggleSource
import com.vayunmathur.youpipe.util.YouPipeViewModel

private val TV_SOURCE_TOGGLES = listOf(

    RecSource.RELATED to R.string.label_source_related,

    RecSource.TRENDING to R.string.label_source_trending,

    RecSource.SUBSCRIPTION to R.string.label_source_subscription,

    RecSource.TOP_CHANNEL to R.string.label_source_top_channel,

    RecSource.SEARCH to R.string.label_source_search,

)



/**

 * TV recommendations settings: presets + dials as steppers + source/filter switches.

 *

 * The phone uses touch sliders and number fields; a TV gets stepper rows (LEFT/RIGHT nudges

 * the value while the row holds focus, CENTER opens nothing) plus the same switches. Dial

 * values persist through the same `setDiscoveryFamiliar`/`setFreshEvergreen`/`setFocusedDiverse`

 * setters — no TV-specific prefs path.

 */

@Composable

fun TvRecommendationsSettingsPage(

    backStack: NavBackStack<TvRoute>,

    ypvm: YouPipeViewModel,

) {

    val recPrefs by ypvm.recommendationPreferences.collectAsState()

    val firstFocus = remember { FocusRequester() }



    TvInitialFocus(firstFocus)

    Column(

        Modifier

            .fillMaxSize()

            .padding(horizontal = 48.dp, vertical = 32.dp)

            .verticalScroll(rememberScrollState()),

        verticalArrangement = Arrangement.spacedBy(8.dp),

    ) {

        Text(text = stringResource(R.string.label_recommendations), style = MaterialTheme.typography.headlineSmall)

        RecommendationPreset.entries.forEachIndexed { index, preset ->

            TvOptionRow(

                label = presetLabel(preset),

                selected = recPrefs.preset == preset.name,

                onSelect = { ypvm.setPreset(preset) },

                modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,

            )

        }

        TvStepperRow(

            label = stringResource(R.string.label_dial_discovery),

            value = recPrefs.discoveryFamiliar,

            onChange = { ypvm.setDiscoveryFamiliar(it) },

        )

        TvStepperRow(

            label = stringResource(R.string.label_dial_fresh),

            value = recPrefs.freshEvergreen,

            onChange = { ypvm.setFreshEvergreen(it) },

        )

        TvStepperRow(

            label = stringResource(R.string.label_dial_diverse),

            value = recPrefs.focusedDiverse,

            onChange = { ypvm.setFocusedDiverse(it) },

        )

        Text(text = stringResource(R.string.label_rec_sources), style = MaterialTheme.typography.titleMedium)

        TV_SOURCE_TOGGLES.forEach { (source, labelRes) ->

            val enabled = when (source) {

                RecSource.RELATED -> recPrefs.sourceRelated

                RecSource.TRENDING -> recPrefs.sourceTrending

                RecSource.SUBSCRIPTION -> recPrefs.sourceSubscription

                RecSource.TOP_CHANNEL -> recPrefs.sourceTopChannel

                RecSource.SEARCH -> recPrefs.sourceSearch

            }

            TvSwitchRow(

                title = stringResource(labelRes),

                checked = enabled,

                onCheckedChange = { ypvm.toggleSource(source) },

            )

        }

        Text(text = stringResource(R.string.label_rec_content_filters), style = MaterialTheme.typography.titleMedium)

        TvSwitchRow(

            title = stringResource(R.string.label_hide_shorts),

            checked = recPrefs.hideShorts,

            onCheckedChange = { ypvm.setHideShorts(it) },

        )

        TvSwitchRow(

            title = stringResource(R.string.label_hide_live),

            checked = recPrefs.hideLive,

            onCheckedChange = { ypvm.setHideLive(it) },

        )

        TvSwitchRow(

            title = stringResource(R.string.label_hide_paid),

            checked = recPrefs.hidePaid,

            onCheckedChange = { ypvm.setHidePaid(it) },

        )

        TvStepperRow(

            label = stringResource(R.string.label_min_duration),

            value = recPrefs.minDurationSec.toFloat(),

            step = 30f,

            range = 0f..3600f,

            onChange = { ypvm.setMinDuration(it.toLong()) },

        )

        TvStepperRow(

            label = stringResource(R.string.label_max_duration),

            value = recPrefs.maxDurationSec.toFloat(),

            step = 60f,

            range = 0f..7200f,

            onChange = { ypvm.setMaxDuration(it.toLong()) },

        )

        TvSettingsRow(

            title = stringResource(R.string.title_manage_interests),

            onClick = { backStack.add(TvRoute.ManageInterests) },

        )

        Button(onClick = { ypvm.resetAlgorithm() }) {

            Text(stringResource(R.string.action_reset_algorithm))

        }

    }

}



/**

 * A 0..1 (or ranged) stepper: LEFT/RIGHT nudges while focused, no drag.

 * Replaces the phone's touch `Slider` and number fields with D-pad steps.

 */

@Composable

private fun TvStepperRow(

    label: String,

    value: Float,

    onChange: (Float) -> Unit,

    modifier: Modifier = Modifier,

    step: Float = 0.1f,

    range: ClosedFloatingPointRange<Float> = 0f..1f,

) {

    Surface(onClick = {}, modifier = modifier.fillMaxWidth()) {

        Row(

            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),

            verticalAlignment = Alignment.CenterVertically,

        ) {

            Text(

                text = label,

                style = MaterialTheme.typography.titleMedium,

                modifier = Modifier.weight(1f),

            )

            Button(onClick = { onChange((value - step).coerceIn(range.start, range.endInclusive)) }) {

                Text("−")

            }

            Text(

                text = if (range.endInclusive <= 1f) {

                    "${(value.coerceIn(range) * 100).toInt()}%"

                } else {

                    "${value.toInt()}s"

                },

                style = MaterialTheme.typography.titleMedium,

                modifier = Modifier.padding(horizontal = 12.dp),

            )

            Button(onClick = { onChange((value + step).coerceIn(range.start, range.endInclusive)) }) {

                Text("+")

            }

        }

    }

}
