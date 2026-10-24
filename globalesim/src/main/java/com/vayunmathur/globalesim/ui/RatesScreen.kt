package com.vayunmathur.globalesim.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.globalesim.domain.QuoteMath
import com.vayunmathur.globalesim.platform.EsimUiState
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.IconRefresh
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.LoadingIndicator
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.rememberMessenger

/**
 * Rate list: what one GB deducts in each country. Display-only — your
 * wallet is charged the local rate automatically as you use data.
 */
@Composable
fun RatesScreen(
    state: EsimUiState,
    onNavigateBack: () -> Unit,
    onRefresh: () -> Unit,
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
    AppScaffold(
        title = "Data rates",
        scrollBehavior = appBarScrollBehavior(),
        onNavigateBack = onNavigateBack,
        actions = {
            IconButton(onClick = onRefresh) { IconRefresh() }
        },
    ) { padding ->
        if (state.loading && state.rates.isEmpty()) {
            LoadingIndicator(Modifier.padding(padding))
            return@AppScaffold
        }
        if (state.rates.isEmpty()) {
            Text("Rates unavailable — pull to refresh.", Modifier.padding(padding).padding(16.dp))
            return@AppScaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(state.rates, key = { it.countryCode }) { rate ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    ListItem(
                        headlineContent = { Text(rate.countryName) },
                        supportingContent = {
                            Text("${QuoteMath.formatCents(rate.pricePerGbUsdCents)} / GB")
                        },
                    )
                }
            }
        }
    }
}
