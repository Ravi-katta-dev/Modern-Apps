package com.vayunmathur.globalesim.ui

import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.stripe.android.PaymentConfiguration
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import com.stripe.android.paymentsheet.rememberPaymentSheet
import com.vayunmathur.globalesim.domain.QuoteMath
import com.vayunmathur.globalesim.platform.EsimUiState
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.LoadingIndicator
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.rememberMessenger

/**
 * Home: your global eSIM at a glance — status, wallet balance, one-tap
 * top-up presets, and links to rates / install / budget / transfer.
 * One eSIM per account: no country picking, no per-trip buying.
 */
@Composable
fun HomeScreen(
    state: EsimUiState,
    onTopUp: (Long) -> Unit,
    onCreateEsim: () -> Unit,
    onStartCardPurchase: (amountCents: Long, publishableKey: String?, onKey: (String) -> Unit, onSheetReady: () -> Unit) -> Unit,
    onCardPurchaseDone: () -> Unit,
    onStartCryptoPurchase: (Long) -> Unit,
    onCheckCryptoStatus: () -> Unit,
    onClearCryptoInvoice: () -> Unit,
    onOpenInstall: () -> Unit,
    onOpenBudget: () -> Unit,
    onOpenTransfer: () -> Unit,
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
    var publishableKey by remember { mutableStateOf<String?>(null) }
    var sheetError by remember { mutableStateOf<String?>(null) }
    val paymentSheet = rememberPaymentSheet { result ->
        when (result) {
            is PaymentSheetResult.Completed -> {
                messenger.show("Payment complete — credit lands shortly.")
                onCardPurchaseDone()
            }
            is PaymentSheetResult.Canceled -> {
                onCardPurchaseDone()
            }
            is PaymentSheetResult.Failed -> {
                sheetError = result.error.localizedMessage ?: "Payment failed"
                onCardPurchaseDone()
            }
        }
    }
    sheetError?.let { messenger.show(it) }
    // Present the sheet when the client secret lands. The secret is cleared
    // on completion/cancel so a recomposition never re-presents.
    val pendingSecret = state.pendingClientSecret
    LaunchedEffect(pendingSecret) {
        if (pendingSecret != null) {
            paymentSheet.presentWithPaymentIntent(
                pendingSecret,
                PaymentSheet.Configuration("Global eSIM"),
            )
        }
    }
    AppScaffold(
        title = "Purchase",
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
        ) {
            val profile = state.profile
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    if (state.loading && profile == null) {
                        LoadingIndicator()
                    } else if (profile == null) {
                        Text("One global eSIM for 200+ countries")
                        Text(
                            "Pay as you go, credit never expires",
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            "No email or phone number or KYC required",
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            "Set up your eSIM now",
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Button(
                            onClick = onCreateEsim,
                            enabled = !state.loading,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        ) {
                            Text("Create my eSIM")
                        }
                    } else {
                        Text("Status: ${profile.status}")
                        Text(
                            "Wallet: ${QuoteMath.formatCents(profile.walletUsdCents)}",
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            "Works in 200+ countries · credit never expires",
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            if (profile != null) {
                TopUpCard(
                    loading = state.loading,
                    onTopUp = onTopUp,
                    onBuyWithCard = { cents ->
                        onStartCardPurchase(
                            cents,
                            publishableKey,
                            { key ->
                                publishableKey = key
                                PaymentConfiguration.init(context, key)
                            },
                            {},
                        )
                    },
                    onBuyWithCrypto = { cents -> onStartCryptoPurchase(cents) },
                    pendingInvoiceUrl = state.pendingInvoiceUrl,
                    pendingBonusCents = state.pendingBonusCents,
                    onCheckCryptoStatus = onCheckCryptoStatus,
                    onClearCryptoInvoice = onClearCryptoInvoice,
                    modifier = Modifier.padding(top = 12.dp),
                )
                OutlinedButton(
                    onClick = onOpenInstall,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    Text("Install / reinstall eSIM")
                }
                OutlinedButton(
                    onClick = onOpenBudget,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text("Credit balance")
                }
                OutlinedButton(
                    onClick = onOpenTransfer,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text("Move to new phone")
                }
            }
        }
    }
}

/**
 * Fixed top-up presets. The button disables while a top-up is in flight
 * so a double-tap cannot double-charge; each attempt carries a fresh
 * idempotency key and the server dedupes replays.
 */
@Composable
private fun TopUpCard(
    loading: Boolean,
    onTopUp: (Long) -> Unit,
    onBuyWithCard: (Long) -> Unit,
    onBuyWithCrypto: (Long) -> Unit,
    pendingInvoiceUrl: String?,
    pendingBonusCents: Long,
    onCheckCryptoStatus: () -> Unit,
    onClearCryptoInvoice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val messenger = rememberMessenger()
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Top up credit")
            Text(
                "Tops up the eSIM wallet directly. ~$1/GB in most countries.",
                modifier = Modifier.padding(top = 4.dp),
            )
            val presets = listOf(500L to "$5", 1000L to "$10", 2000L to "$20")
            for ((cents, label) in presets) {
                Button(
                    onClick = { onTopUp(cents) },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text("Add $label credit")
                }
            }
            Text(
                "Or pay by card — credit lands automatically after payment.",
                modifier = Modifier.padding(top = 8.dp),
            )
            for ((cents, label) in presets) {
                OutlinedButton(
                    onClick = { onBuyWithCard(cents) },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text("Buy $label with card")
                }
            }
            Text(
                "Or pay with crypto and get 5% extra credit free — " +
                    "e.g. pay $10, get $10.50. No account needed.",
                modifier = Modifier.padding(top = 8.dp),
            )
            if (pendingInvoiceUrl != null) {
                Text(
                    "Checkout open in your browser. Come back here when you've paid.",
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (pendingBonusCents > 0) {
                    Text(
                        "Bonus on this payment: +${QuoteMath.formatCents(pendingBonusCents)}.",
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Button(
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, pendingInvoiceUrl.toUri()))
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text("Reopen checkout")
                }
                OutlinedButton(
                    onClick = {
                        onCheckCryptoStatus()
                        messenger.show("Checking payment…")
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text("I've paid — check status")
                }
                OutlinedButton(
                    onClick = onClearCryptoInvoice,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text("Cancel crypto payment")
                }
            } else {
                for ((cents, label) in presets) {
                    OutlinedButton(
                        onClick = { onBuyWithCrypto(cents) },
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        Text("Buy $label with crypto (+5%)")
                    }
                }
            }
        }
    }
}
