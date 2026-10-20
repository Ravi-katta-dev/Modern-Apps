package com.vayunmathur.youpipe.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.util.exportSubscriptions
import com.vayunmathur.youpipe.util.importNewPipe
import com.vayunmathur.youpipe.util.importYouTubeTakeout
import com.vayunmathur.youpipe.util.restoreSubscriptions
import com.vayunmathur.youpipe.util.YouPipeViewModel

/**

 * TV data settings: clear-history (with confirm step) + import/export rows.

 *

 * The phone launches SAF pickers via activity-result; on TV the same launchers fire — the

 * system file picker is itself D-pad traversable on Google TV. Import progress shows inline.

 */

@Composable

fun TvDataSettingsPage(

    backStack: NavBackStack<TvRoute>,

    ypvm: YouPipeViewModel,

) {

    val isLoading by ypvm.isImporting.collectAsState()

    val progress by ypvm.importProgress.collectAsState()

    var confirmClear by remember { mutableStateOf(false) }

    val firstFocus = remember { FocusRequester() }



    val youtubeLauncher = androidx.activity.compose.rememberLauncherForActivityResult(

        androidx.activity.result.contract.ActivityResultContracts.GetContent(),

    ) { uri -> if (uri != null) ypvm.importYouTubeTakeout(uri) }

    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(

        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),

    ) { uri -> if (uri != null) ypvm.exportSubscriptions(uri) }

    val restoreLauncher = androidx.activity.compose.rememberLauncherForActivityResult(

        androidx.activity.result.contract.ActivityResultContracts.GetContent(),

    ) { uri -> if (uri != null) ypvm.restoreSubscriptions(uri) }

    val newPipeLauncher = androidx.activity.compose.rememberLauncherForActivityResult(

        androidx.activity.result.contract.ActivityResultContracts.GetContent(),

    ) { uri -> if (uri != null) ypvm.importNewPipe(uri) }



    TvInitialFocus(firstFocus)

    Column(

        Modifier

            .fillMaxSize()

            .padding(horizontal = 48.dp, vertical = 32.dp)

            .verticalScroll(rememberScrollState()),

        verticalArrangement = Arrangement.spacedBy(8.dp),

    ) {

        Text(text = stringResource(R.string.settings_data), style = MaterialTheme.typography.headlineSmall)

        if (isLoading) {

            com.vayunmathur.library.ui.CircularProgressIndicator(progress = { progress })

        }

        Text(text = stringResource(R.string.title_history), style = MaterialTheme.typography.titleMedium)

        if (confirmClear) {

            Text(

                text = stringResource(R.string.are_you_sure_you_want_to_clear_all_watch),

                style = MaterialTheme.typography.bodyMedium,

            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {

                Button(

                    onClick = { ypvm.clearHistory(); confirmClear = false },

                    modifier = Modifier.focusRequester(firstFocus),

                ) {

                    Text(stringResource(UiR.string.clear))

                }

                Button(onClick = { confirmClear = false }) {

                    Text(stringResource(UiR.string.cancel))

                }

            }

        } else {

            TvSettingsRow(

                title = stringResource(R.string.clear_history),

                onClick = { confirmClear = true },

                modifier = Modifier.focusRequester(firstFocus),

            )

        }

        Text(text = stringResource(R.string.label_backup_restore), style = MaterialTheme.typography.titleMedium)

        TvSettingsRow(

            title = stringResource(R.string.label_export_youpipe),

            onClick = { exportLauncher.launch("youpipe_subscriptions.json") },

        )

        TvSettingsRow(

            title = stringResource(R.string.label_restore_youpipe),

            onClick = { restoreLauncher.launch("application/json") },

        )

        TvSettingsRow(

            title = stringResource(R.string.label_import_newpipe),

            onClick = { newPipeLauncher.launch("application/json") },

        )

        TvSettingsRow(

            title = stringResource(R.string.label_import_youtube),

            onClick = { youtubeLauncher.launch("application/zip") },

        )

    }

}
