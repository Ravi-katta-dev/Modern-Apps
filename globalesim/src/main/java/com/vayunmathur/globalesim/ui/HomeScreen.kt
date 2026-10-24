package com.vayunmathur.globalesim.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.stripe.android.PaymentConfiguration
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import com.stripe.android.paymentsheet.rememberPaymentSheet
import com.vayunmathur.globalesim.domain.QuoteMath
import com.vayunmathur.globalesim.domain.buildActivationCode
import com.vayunmathur.globalesim.platform.EsimUiState
import com.vayunmathur.library.intents.euicc.EsimLink
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.LoadingIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.rememberMessenger
import kotlin.math.roundToLong

/**
 * Home: your global eSIM at a glance — status, wallet balance, one-tap
 * top-up presets, and links to rates / install / budget / transfer.
 * One eSIM per account: no country picking, no per-trip buying.
 */
@Composable
fun HomeScreen(
    state: EsimUiState,
    onCreateEsim: () -> Unit,
    onStartCardPurchase: (amountCents: Long, publishableKey: String?, onKey: (String) -> Unit, onSheetReady: () -> Unit) -> Unit,
    onCardPurchaseDone: () -> Unit,
    onStartCryptoPurchase: (Long) -> Unit,
    onCheckCryptoStatus: () -> Unit,
    onClearCryptoInvoice: () -> Unit,
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
                            "Balance: ${QuoteMath.formatCents(profile.walletUsdCents)}",
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            if (profile != null) {
                TopUpCard(
                    loading = state.loading,
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
                    onClick = {
                        // The vendor stores SM-DP+ and matching ID separately;
                        // the LPA needs the full LPA:1$<smdp>$<code> string.
                        // A bare matching ID fails validation and lands on a
                        // blank manual-entry page. The server sometimes fuses
                        // smdp+code with the `$` missing, so normalize first.
                        val full = buildActivationCode(profile.activationCode, profile.smdpAddress)
                        // TEMP-DEBUG: part counts only, never the code itself.
                        com.vayunmathur.library.log.Log.status(
                            "Esim",
                            "install len=${full.length} parts=${full.split('$').size} " +
                                "smdpLen=${profile.smdpAddress.length} codeLen=${profile.activationCode.length}",
                        )
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            EsimLink.provisioningUri(full),
                        ).setPackage(EsimLink.LPA_PACKAGE)
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    Text("Install / reinstall eSIM")
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
 * Custom-amount top-up. The user types any dollar amount; two side-by-side
 * buttons pay by card ($5 min) or crypto ($2 min, +5% bonus), both capped
 * at $20. Payment funds the eSIM wallet directly — there is no manual
 * move step and no separate credit pool.
 */
@Composable
private fun TopUpCard(
    loading: Boolean,
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
    var amountText by remember { mutableStateOf("") }
    // Parse "$12.34" / "12.34" to integer cents; null while invalid.
    val amountCents: Long? = remember(amountText) {
        val cleaned = amountText.trim().removePrefix("$")
        if (cleaned.isEmpty()) null
        else cleaned.toDoubleOrNull()?.let { (it * 100).roundToLong() }?.takeIf { it > 0 }
    }
    val cardOk = amountCents != null && amountCents in 500..2000
    val cryptoOk = amountCents != null && amountCents in 200..2000
    // Inline validation: only when the field is non-empty and parses.
    // Card floor is $5, but the message is rail-agnostic per spec —
    // the card button simply stays disabled below $5.
    val rangeError: String? = when {
        amountText.isBlank() || amountCents == null -> null
        amountCents < 200 -> "Minimum $2 payment."
        amountCents > 2000 -> "$20 max payment."
        else -> null
    }
    // Live bonus preview: 5% of the entered amount.
    val bonusPreview: Long? = amountCents?.let { (it * 105) / 100 - it }?.takeIf { cryptoOk }
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Top up")
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text("Amount (USD)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                isError = rangeError != null,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            if (rangeError != null) {
                Text(
                    rangeError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (bonusPreview != null && bonusPreview > 0) {
                Text(
                    "Crypto bonus: +${QuoteMath.formatCents(bonusPreview)} extra.",
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onBuyWithCard(amountCents!!) },
                    enabled = !loading && cardOk,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Card topup")
                }
                BadgedBox(badge = "+5%", modifier = Modifier.weight(1f)) {
                    Button(
                        onClick = { onBuyWithCrypto(amountCents!!) },
                        enabled = !loading && cryptoOk,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Crypto topup")
                    }
                }
            }
            if (pendingInvoiceUrl != null) {
                Text(
                    "Checkout open in your browser. Come back here when you've paid.",
                    modifier = Modifier.padding(top = 8.dp),
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
            }
        }
    }
}

/** Small pill badge overlapped on the top-end corner of [content]. */
@Composable
private fun BadgedBox(
    badge: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier) {
        content()
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-8).dp),
        ) {
            Text(
                badge,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
