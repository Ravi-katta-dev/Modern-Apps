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
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.util.YouPipeViewModel
import com.vayunmathur.youpipe.util.YouPipeViewModel.Companion.ALL_SPONSOR_CATEGORIES

/**

 * TV SponsorBlock settings: one switch row per category.

 * Same `ALL_SPONSOR_CATEGORIES` + `toggleSponsorBlockCategory` as the phone page.

 */

@Composable

fun TvSponsorBlockSettingsPage(

    backStack: NavBackStack<TvRoute>,

    ypvm: YouPipeViewModel,

) {

    val sponsorBlockCategories by ypvm.sponsorBlockCategories.collectAsState()

    val firstFocus = remember { FocusRequester() }



    TvInitialFocus(firstFocus)

    Column(

        Modifier

            .fillMaxSize()

            .padding(horizontal = 48.dp, vertical = 32.dp)

            .verticalScroll(rememberScrollState()),

        verticalArrangement = Arrangement.spacedBy(8.dp),

    ) {

        Text(text = stringResource(R.string.settings_sponsorblock), style = MaterialTheme.typography.headlineSmall)

        ALL_SPONSOR_CATEGORIES.forEachIndexed { index, category ->

            TvSwitchRow(

                title = YouPipeViewModel.Companion.SPONSOR_CATEGORY_LABELS[category] ?: category,

                checked = category in sponsorBlockCategories,

                onCheckedChange = { ypvm.toggleSponsorBlockCategory(category) },

                modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,

            )

        }

    }

}
