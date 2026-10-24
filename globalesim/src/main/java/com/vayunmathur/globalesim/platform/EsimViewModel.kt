package com.vayunmathur.globalesim.platform

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.globalesim.data.AnonymousIdStore
import com.vayunmathur.globalesim.data.api.EsimApi
import com.vayunmathur.globalesim.data.api.ProfileDto
import com.vayunmathur.globalesim.data.api.RateDto
import com.vayunmathur.globalesim.data.api.TopUpRequest
import java.util.UUID
import kotlinx.coroutines.launch

/** UI state for the single-eSIM storefront. */
data class EsimUiState(
    val loading: Boolean = false,
    val profile: ProfileDto? = null,
    val rates: List<RateDto> = emptyList(),
    val budgetCents: Long? = null,
    val transferCode: String? = null,
    val pendingClientSecret: String? = null,
    val pendingAmountCents: Long = 0,
    /** Pending crypto invoice (url + id + bonus); cleared when paid/closed. */
    val pendingInvoiceUrl: String? = null,
    val pendingInvoiceId: String? = null,
    val pendingBonusCents: Long = 0,
    val error: String? = null,
)

class EsimViewModel(app: Application) : AndroidViewModel(app) {
    private val idStore = AnonymousIdStore(app.applicationContext)

    var state by mutableStateOf(EsimUiState())
        private set

    init {
        viewModelScope.launch {
            // Mint the anonymous id only. Provisioning happens on explicit
            // user action (createEsim) — never on launch, since each
            // provision is a real $1.75 vendor charge.
            idStore.getOrCreate()
            refreshAll()
        }
    }

    private suspend fun accountId(): String = idStore.getOrCreate()

    /**
     * Explicit user action: provision the account's one eSIM. Each call is
     * a real vendor charge, so the UI gates this behind a confirmation
     * button — it is NEVER called on launch. Idempotent server-side:
     * re-tapping an existing account returns its profile.
     */
    fun createEsim() {
        viewModelScope.launch {
            state = state.copy(loading = true, error = null)
            runCatching { EsimApi.register(accountId()) }
                .onSuccess { refreshAll() }
                .onFailure { state = state.copy(loading = false, error = it.message) }
        }
    }

    fun dismissError() {
        state = state.copy(error = null)
    }

    fun refreshAll() {
        refreshStatus()
        refreshRates()
        refreshBudget()
    }

    fun refreshStatus() {
        viewModelScope.launch {
            state = state.copy(loading = true, error = null)
            runCatching { EsimApi.status(accountId()) }
                .onSuccess { state = state.copy(loading = false, profile = it.profile) }
                .onFailure { state = state.copy(loading = false, error = it.message) }
        }
    }

    fun refreshRates() {
        viewModelScope.launch {
            runCatching { EsimApi.rates() }
                .onSuccess { state = state.copy(rates = it.rates) }
                .onFailure { state = state.copy(error = it.message) }
        }
    }

    fun refreshBudget() {
        viewModelScope.launch {
            runCatching { EsimApi.budget(accountId()) }
                .onSuccess { state = state.copy(budgetCents = it.budgetUsdCents) }
                .onFailure { state = state.copy(error = it.message) }
        }
    }

    /**
     * Move credit from budget to the on-SIM wallet. One attempt per call;
     * the caller disables the button while [EsimUiState.loading] so a
     * double-tap cannot double-charge. Each attempt carries a fresh
     * idempotency key and the server dedupes replays.
     */
    fun topUp(amountCents: Long) {
        viewModelScope.launch {
            state = state.copy(loading = true, error = null)
            val req = TopUpRequest(accountId(), amountCents, UUID.randomUUID().toString())
            runCatching { EsimApi.topUp(req) }
                .onSuccess {
                    state = state.copy(
                        loading = false,
                        budgetCents = it.budgetUsdCents,
                        profile = state.profile?.copy(walletUsdCents = it.walletUsdCents),
                    )
                    refreshStatus()
                }
                .onFailure { state = state.copy(loading = false, error = it.message) }
        }
    }

    fun setEnabled(iccid: String, enabled: Boolean) {
        viewModelScope.launch {
            runCatching {
                if (enabled) EsimApi.enable(accountId(), iccid)
                else EsimApi.disable(accountId(), iccid)
            }
                .onSuccess { refreshStatus() }
                .onFailure { state = state.copy(error = it.message) }
        }
    }

    fun transferOut() {
        viewModelScope.launch {
            state = state.copy(loading = true, error = null, transferCode = null)
            runCatching { EsimApi.transferOut(accountId()) }
                .onSuccess { state = state.copy(loading = false, transferCode = it.transferCode) }
                .onFailure { state = state.copy(loading = false, error = it.message) }
        }
    }

    /**
     * Start a card purchase: fetch the publishable key (once per process)
     * and a PaymentIntent for [amountCents]. The screen presents the
     * PaymentSheet with the resulting client secret; the server credits
     * the budget via the Stripe webhook, and [onSheetReady] fires so the
     * screen can present immediately.
     */
    fun startCardPurchase(amountCents: Long, publishableKey: String?, onKey: (String) -> Unit, onSheetReady: () -> Unit) {
        viewModelScope.launch {
            state = state.copy(loading = true, error = null, pendingClientSecret = null)
            runCatching {
                val key = publishableKey ?: EsimApi.stripeKey().publishableKey
                val intent = EsimApi.stripeIntent(accountId(), amountCents)
                Triple(key, intent.clientSecret, amountCents)
            }
                .onSuccess { (key, secret, amount) ->
                    onKey(key)
                    state = state.copy(
                        loading = false,
                        pendingClientSecret = secret,
                        pendingAmountCents = amount,
                    )
                    onSheetReady()
                }
                .onFailure { state = state.copy(loading = false, error = it.message) }
        }
    }

    /** Clear the pending sheet secret after it completes or is cancelled. */
    fun clearPendingPurchase() {
        state = state.copy(pendingClientSecret = null, pendingAmountCents = 0, loading = false)
    }

    /**
     * Start a crypto purchase: create a NOWPayments invoice for [amountCents]
     * face value. The screen opens the hosted checkout URL; the server
     * credits the budget + 5% bonus via the IPN webhook. [onInvoiceReady]
     * fires with the checkout URL to open.
     */
    fun startCryptoPurchase(amountCents: Long, onInvoiceReady: (String) -> Unit) {
        viewModelScope.launch {
            state = state.copy(
                loading = true, error = null,
                pendingInvoiceUrl = null, pendingInvoiceId = null, pendingBonusCents = 0,
            )
            runCatching { EsimApi.nowPayInvoice(accountId(), amountCents) }
                .onSuccess {
                    state = state.copy(
                        loading = false,
                        pendingInvoiceUrl = it.invoiceUrl,
                        pendingInvoiceId = it.invoiceId,
                        pendingBonusCents = it.bonusUsdCents,
                        pendingAmountCents = amountCents,
                    )
                    onInvoiceReady(it.invoiceUrl)
                }
                .onFailure { state = state.copy(loading = false, error = it.message) }
        }
    }

    /** Poll the invoice status after the user returns from checkout. */
    fun checkCryptoStatus(onResult: (String) -> Unit) {
        val invoiceId = state.pendingInvoiceId ?: return
        viewModelScope.launch {
            runCatching { EsimApi.nowPayStatus(accountId(), invoiceId) }
                .onSuccess {
                    if (it.status == "finished") {
                        state = state.copy(pendingInvoiceUrl = null, pendingInvoiceId = null)
                        refreshBudget()
                        refreshAll()
                    }
                    onResult(it.status)
                }
                .onFailure { state = state.copy(error = it.message) }
        }
    }

    /** Clear the pending crypto invoice (user closed checkout). */
    fun clearPendingInvoice() {
        state = state.copy(
            pendingInvoiceUrl = null, pendingInvoiceId = null,
            pendingBonusCents = 0, pendingAmountCents = 0, loading = false,
        )
    }

    fun transferIn(oldAccountId: String, code: String, onDone: () -> Unit) {
        viewModelScope.launch {
            state = state.copy(loading = true, error = null)
            val fresh = UUID.randomUUID().toString()
            runCatching { EsimApi.transferIn(oldAccountId, code.trim(), fresh) }
                .onSuccess {
                    idStore.replace(fresh)
                    state = state.copy(loading = false, transferCode = null)
                    refreshAll()
                    onDone()
                }
                .onFailure { state = state.copy(loading = false, error = it.message) }
        }
    }
}
