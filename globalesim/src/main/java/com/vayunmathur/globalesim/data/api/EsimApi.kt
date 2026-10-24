package com.vayunmathur.globalesim.data.api

import com.vayunmathur.library.network.NetworkClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * Thin client for our own eSIM storefront API (the esim-v1 routes on
 * api.maproj.org, FIRST_PARTY trust). Generic DTOs only — the reseller
 * vendor is contacted server-side and is never named here.
 */
object EsimApi {
    const val BASE_URL = "https://api.maproj.org"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun url(path: String): String = BASE_URL + path

    private suspend inline fun <reified Req, reified Res> post(path: String, body: Req): Res =
        NetworkClient.callJson(
            url = url(path),
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
            body = json.encodeToString(serializer<Req>(), body),
        )

    private suspend inline fun <reified Res> get(path: String): Res =
        NetworkClient.getJson(url(path))

    suspend fun register(accountId: String): RegisterResponse =
        post("/esim/v1/register", RegisterRequest(accountId))

    suspend fun rates(): RatesResponse = get("/esim/v1/rates")

    suspend fun status(accountId: String): StatusResponse =
        post("/esim/v1/status", AccountRequest(accountId))

    suspend fun detail(accountId: String, iccid: String): ProfileDto =
        post("/esim/v1/detail", ManageRequest(accountId, iccid))

    suspend fun topUp(req: TopUpRequest): TopUpResponse = post("/esim/v1/topup", req)

    suspend fun enable(accountId: String, iccid: String): ManageResponse =
        post("/esim/v1/enable", ManageRequest(accountId, iccid))

    suspend fun disable(accountId: String, iccid: String): ManageResponse =
        post("/esim/v1/disable", ManageRequest(accountId, iccid))

    suspend fun transferOut(accountId: String): TransferOutResponse =
        post("/esim/v1/transfer-out", TransferOutRequest(accountId))

    suspend fun transferIn(oldAccountId: String, code: String, newAccountId: String): TransferInResponse =
        post("/esim/v1/transfer-in", TransferInRequest(oldAccountId, code, newAccountId))

    suspend fun budget(accountId: String): BudgetResponse =
        post("/esim/v1/budget", AccountRequest(accountId))

    suspend fun stripeKey(): StripeKeyResponse = get("/esim/v1/stripe-key")

    suspend fun stripeIntent(accountId: String, amountCents: Long): StripeIntentResponse =
        post("/esim/v1/stripe-intent", StripeIntentRequest(accountId, amountCents))

    suspend fun nowPayInvoice(accountId: String, amountCents: Long): NowPayInvoiceResponse =
        post("/esim/v1/nowpay-invoice", NowPayInvoiceRequest(accountId, amountCents))

    suspend fun nowPayStatus(accountId: String, invoiceId: String): NowPayStatusResponse =
        post("/esim/v1/nowpay-status", NowPayStatusRequest(accountId, invoiceId))
}
