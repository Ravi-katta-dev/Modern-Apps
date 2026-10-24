package com.vayunmathur.globalesim.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text

/** Claim an eSIM onto this phone with the SIM ID copied from the other phone. */
@Composable
fun ClaimCard(busy: Boolean, onClaim: (String) -> Unit, modifier: Modifier = Modifier) {
    var simId by rememberSaveable { mutableStateOf("") }
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Already have an eSIM on another phone?", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = simId,
                onValueChange = { simId = it },
                label = { Text("SIM ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedButton(
                onClick = { onClaim(simId) },
                enabled = !busy && simId.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text("Claim on this phone")
            }
        }
    }
}
