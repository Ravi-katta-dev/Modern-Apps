package com.vayunmathur.globalesim.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.globalesim.platform.EsimUiState
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.LoadingIndicator
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.rememberMessenger

/**
 * Device transfer: show a one-time code on the old phone, enter old id +
 * code on the new phone. The server moves the eSIM + credit and rotates
 * the code — codes are single-use.
 */
@Composable
fun TransferScreen(
    state: EsimUiState,
    onTransferOut: () -> Unit,
    onTransferIn: (oldAccountId: String, code: String) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val messenger = rememberMessenger()
    val error = state.error
    // Shown once per failure, not on every recomposition.
    LaunchedEffect(error) {
        if (error != null) messenger.show(error)
    }
    var oldId by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    AppScaffold(
        title = "Move to new phone",
        scrollBehavior = appBarScrollBehavior(),
        onNavigateBack = onNavigateBack,
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("On this (old) phone")
                    val shown = state.transferCode
                    if (shown != null) {
                        Text("Your code: $shown", modifier = Modifier.padding(top = 8.dp))
                        Text("Enter it on the new phone. Single use.")
                    } else {
                        Button(
                            onClick = onTransferOut,
                            enabled = !state.loading,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) {
                            if (state.loading) LoadingIndicator() else Text("Show transfer code")
                        }
                    }
                }
            }
            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("On the new phone")
                    OutlinedTextField(
                        value = oldId,
                        onValueChange = { oldId = it },
                        label = { Text("Old account id") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("Transfer code") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Button(
                        onClick = { onTransferIn(oldId, code) },
                        enabled = !state.loading && oldId.isNotBlank() && code.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) {
                        Text("Claim eSIM on this phone")
                    }
                }
            }
        }
    }
}
