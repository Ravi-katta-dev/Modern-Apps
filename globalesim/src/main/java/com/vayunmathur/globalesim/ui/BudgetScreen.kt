package com.vayunmathur.globalesim.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.rememberMessenger

/**
 * Credit balance: the user-paid pool that top-ups draw from. Newly moved
 * credit can take ~15 minutes to show full speed while the network
 * confirms payment. (Stripe card top-up lands here in Phase 4.)
 */
@Composable
fun BudgetScreen(
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
        title = "Credit",
        scrollBehavior = appBarScrollBehavior(),
        onNavigateBack = onNavigateBack,
        actions = {
            IconButton(onClick = onRefresh) { IconRefresh() }
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    val cents = state.budgetCents
                    Text(
                        if (cents == null) "Balance unknown — pull to refresh."
                        else "Credit: ${QuoteMath.formatCents(cents)}",
                    )
                    val wallet = state.profile?.walletUsdCents
                    if (wallet != null) {
                        Text(
                            "On eSIM: ${QuoteMath.formatCents(wallet)}",
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (cents != null && cents < 500) {
                        Text(
                            "Low credit: top up from Home before your wallet runs out.",
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
