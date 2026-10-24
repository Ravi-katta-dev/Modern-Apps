package com.vayunmathur.globalesim.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vayunmathur.globalesim.platform.EsimUiState
import com.vayunmathur.library.intents.euicc.EsimLink
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.LoadingIndicator
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.rememberMessenger

/**
 * Install: one-tap direct install for the account's single eSIM. Goes
 * straight through the on-device eSIM manager ([EsimLink.provisioningUri]
 * pinned to the LPA package) — this app never duplicates LPA logic and
 * shows no code or QR. After a transfer, the same button installs the
 * reissued profile on the new phone.
 */
@Composable
fun InstallScreen(
    state: EsimUiState,
    onNavigateBack: () -> Unit,
    onDismissError: () -> Unit,
) {
    val messenger = rememberMessenger()
    val error = state.error
    LaunchedEffect(error) {
        if (error != null) {
            messenger.show(error)
            onDismissError()
        }
    }
    val context = LocalContext.current
    val profile = state.profile
    AppScaffold(
        title = "Install eSIM",
        scrollBehavior = appBarScrollBehavior(),
        onNavigateBack = onNavigateBack,
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            if (state.loading && profile == null) {
                LoadingIndicator()
                return@Column
            }
            if (profile == null || profile.activationCode.isEmpty()) {
                Text("Your eSIM is being created — check back in a minute.")
                return@Column
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Status: ${profile.status}")
                    Text(
                        "One tap installs your global eSIM. It works in 200+ countries.",
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Button(
                onClick = {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        EsimLink.provisioningUri(profile.activationCode),
                    ).setPackage(EsimLink.LPA_PACKAGE)
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                Text("Install eSIM")
            }
            Text(
                "Already installed on another phone? Move it with a transfer code instead of installing twice.",
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
