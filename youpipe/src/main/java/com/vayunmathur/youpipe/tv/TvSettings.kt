package com.vayunmathur.youpipe.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.ui.presetLabel
import com.vayunmathur.youpipe.util.RecommendationPreset
import com.vayunmathur.youpipe.util.YouPipeViewModel
import com.vayunmathur.youpipe.util.YouPipeViewModel.Companion.ALL_SPONSOR_CATEGORIES

/**

 * TV settings hub: the four sections as focusable rows.

 *

 * Mirrors the phone `SettingsPage` (sponsor summary + recommendation preset summary) with

 * `TvSettingsRow` targets — no app bars, the tab strip is the chrome.

 */

@Composable

fun TvSettingsPage(

    backStack: NavBackStack<TvRoute>,

    ypvm: YouPipeViewModel,

) {

    val sponsorBlockCategories by ypvm.sponsorBlockCategories.collectAsState()

    val recPrefs by ypvm.recommendationPreferences.collectAsState()

    val blockedCount = sponsorBlockCategories.count { it in ALL_SPONSOR_CATEGORIES }

    val currentPreset = RecommendationPreset.entries.firstOrNull { it.name == recPrefs.preset }

    val presetSummary = currentPreset?.let { presetLabel(it) } ?: stringResource(R.string.preset_custom)

    val firstFocus = remember { FocusRequester() }



    TvInitialFocus(firstFocus)

    Column(

        Modifier

            .fillMaxSize()

            .padding(horizontal = 48.dp, vertical = 32.dp)

            .verticalScroll(rememberScrollState()),

        verticalArrangement = Arrangement.spacedBy(8.dp),

    ) {

        Text(text = stringResource(UiR.string.settings), style = MaterialTheme.typography.headlineSmall)

        TvSettingsRow(

            title = stringResource(R.string.settings_general_content),

            onClick = { backStack.add(TvRoute.SettingsGeneral) },

            modifier = Modifier.focusRequester(firstFocus),

        )

        TvSettingsRow(

            title = stringResource(R.string.settings_sponsorblock),

            summary = stringResource(

                R.string.settings_sponsorblock_summary,

                blockedCount,

                ALL_SPONSOR_CATEGORIES.size,

            ),

            onClick = { backStack.add(TvRoute.SettingsSponsorBlock) },

        )

        TvSettingsRow(

            title = stringResource(R.string.label_recommendations),

            summary = presetSummary,

            onClick = { backStack.add(TvRoute.SettingsRecommendations) },

        )

        TvSettingsRow(

            title = stringResource(R.string.settings_data),

            onClick = { backStack.add(TvRoute.SettingsData) },

        )

    }

}
