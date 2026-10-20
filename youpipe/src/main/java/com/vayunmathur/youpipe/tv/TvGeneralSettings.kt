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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.util.YouPipeViewModel

/**

 * TV general settings: open-to default, DeArrow, keep-controls, notification bar, language.

 *

 * Same DataStore bindings as the phone `GeneralSettingsPage`; dropdowns become D-pad option

 * lists (one focusable row per choice) because a TV has no dropdown menu convention.

 */

@Composable

fun TvGeneralSettingsPage(

    backStack: NavBackStack<TvRoute>,

    ypvm: YouPipeViewModel,

) {

    val deArrowEnabled by ypvm.deArrowEnabled.collectAsState()

    val youtubeLanguage by ypvm.youtubeLanguage.collectAsState()

    val defaultPage by ypvm.defaultPage.collectAsState()

    val keepControlsVisible by ypvm.keepPlayerControlsVisible.collectAsState()

    val notificationStyleProgress by ypvm.notificationStyleProgressBar.collectAsState()

    val firstFocus = remember { FocusRequester() }



    // TV pins the transport overlay visible when the accessibility keep-visible is on —

    // same flag the phone player reads, no TV-specific path.

    androidx.compose.runtime.LaunchedEffect(keepControlsVisible) {

        TvTransportState.pinned = keepControlsVisible

        if (keepControlsVisible) TvTransportState.reveal()

    }



    TvInitialFocus(firstFocus)

    Column(

        Modifier

            .fillMaxSize()

            .padding(horizontal = 48.dp, vertical = 32.dp)

            .verticalScroll(rememberScrollState()),

        verticalArrangement = Arrangement.spacedBy(8.dp),

    ) {

        Text(text = stringResource(R.string.settings_general_content), style = MaterialTheme.typography.headlineSmall)

        Text(text = stringResource(R.string.label_default_page), style = MaterialTheme.typography.titleMedium)

        com.vayunmathur.youpipe.DEFAULT_PAGE_OPTIONS.forEach { (key, labelRes) ->

            TvOptionRow(

                label = stringResource(labelRes),

                selected = defaultPage == key,

                onSelect = { ypvm.setDefaultPage(key) },

            )

        }

        TvSwitchRow(

            title = stringResource(R.string.label_dearrow),

            summary = stringResource(R.string.label_dearrow_description),

            checked = deArrowEnabled,

            onCheckedChange = { ypvm.setDeArrowEnabled(it) },

        )

        TvSwitchRow(

            title = stringResource(R.string.label_keep_controls_visible),

            summary = stringResource(R.string.label_keep_controls_visible_description),

            checked = keepControlsVisible,

            onCheckedChange = { ypvm.setKeepPlayerControlsVisible(it) },

        )

        TvSwitchRow(

            title = stringResource(R.string.label_notification_style_progress),

            summary = stringResource(R.string.label_notification_style_progress_description),

            checked = notificationStyleProgress,

            onCheckedChange = { ypvm.setNotificationStyleProgressBar(it) },

        )

        Text(text = stringResource(R.string.label_youtube_language), style = MaterialTheme.typography.titleMedium)

        TvOptionRow(

            label = stringResource(R.string.label_system_default),

            selected = youtubeLanguage.isBlank(),

            onSelect = { ypvm.setYouTubeLanguage("") },

        )

        YouPipeViewModel.Companion.YOUTUBE_LANGUAGES.forEach { (code, name) ->

            TvOptionRow(

                label = name,

                selected = youtubeLanguage == code,

                onSelect = { ypvm.setYouTubeLanguage(code) },

            )

        }

    }

}



/** One selectable option row with a checked marker. Shared with recommendations (presets). */
@Composable
internal fun TvOptionRow(

    label: String,

    selected: Boolean,

    onSelect: () -> Unit,

    modifier: Modifier = Modifier,

) {

    Surface(onClick = onSelect, modifier = modifier.fillMaxWidth()) {

        Row(

            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),

            verticalAlignment = Alignment.CenterVertically,

        ) {

            androidx.tv.material3.Checkbox(checked = selected, onCheckedChange = null)

            Text(

                text = label,

                style = MaterialTheme.typography.titleMedium,

                modifier = Modifier.padding(start = 12.dp),

            )

        }

    }

}
