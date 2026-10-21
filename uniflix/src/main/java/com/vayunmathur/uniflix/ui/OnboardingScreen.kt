package com.vayunmathur.uniflix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior

/** First-run: enter your own TMDB API key. Nothing else works without it. */
@Composable
fun OnboardingScreen(
    draftKey: String,
    saving: Boolean,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    AppScaffold(title = "Uniflix", scrollBehavior = appBarScrollBehavior()) { padding ->
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Add your TMDB API key to browse the catalog.")
                OutlinedTextField(
                    value = draftKey,
                    onValueChange = onDraftChange,
                    label = { Text("TMDB API key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Button(onClick = onSave, enabled = !saving && draftKey.isNotBlank()) {
                    Text("Save and continue")
                }
            }
        }
    }
}
