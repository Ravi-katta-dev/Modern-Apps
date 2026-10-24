package com.vayunmathur.globalesim.data.api

import kotlinx.serialization.Serializable

/**
 * Generic eSIM storefront DTOs. These mirror our own esim-v1 API on
 * api.maproj.org — they contain no vendor names, URLs, or key material.
 * The reseller vendor is contacted server-side only and is never named here.
 *
 * Product model: one global eSIM per anonymous account. The user tops up
 * USD credit; data deducts from the on-SIM wallet at the local per-country
 * rate. Credit never expires.
 */

@Serializable
data class RegisterRequest(val accountId: String)

@Serializable
data class RegisterResponse(val ok: Boolean = false)

@Serializable
data class RateDto(
    val countryCode: String,
    val countryName: String,
    /** Integer USD cents per GB. Integer math only — no float money. */
    val pricePerGbUsdCents: Long,
)

@Serializable
data class RatesResponse(val rates: List<RateDto> = emptyList())

@Serializable
data class AccountRequest(val accountId: String)

/** The account's single global eSIM (null when not yet provisioned). */
@Serializable
data class ProfileDto(
    val iccid: String,
    val status: String,
    val activationCode: String = "",
    val smdpAddress: String = "",
    /** On-SIM wallet balance in USD cents. */
    val walletUsdCents: Long = 0,
)

@Serializable
data class StatusResponse(val profile: ProfileDto? = null)

@Serializable
data class TopUpRequest(
    val accountId: String,
    /** USD cents to move from budget to the on-SIM wallet. */
    val amountCents: Long,
    /** Client-minted UUIDv4 per tap; the server dedupes replays. */
    val idempotencyKey: String,
)

@Serializable
data class TopUpResponse(
    val walletUsdCents: Long = 0,
    val budgetUsdCents: Long = 0,
)

@Serializable
data class ManageRequest(val accountId: String, val iccid: String)

@Serializable
data class ManageResponse(val ok: Boolean = false)

@Serializable
data class TransferOutRequest(val accountId: String)

@Serializable
data class TransferOutResponse(val transferCode: String = "")

@Serializable
data class TransferInRequest(val oldAccountId: String, val code: String, val newAccountId: String)

@Serializable
data class TransferInResponse(val ok: Boolean = false)

@Serializable
data class BudgetResponse(
    /** User-paid credit pool in USD cents. */
    val budgetUsdCents: Long = 0,
)

@Serializable
data class StripeKeyResponse(val publishableKey: String = "")

@Serializable
data class StripeIntentRequest(val accountId: String, val amountCents: Long)

@Serializable
data class StripeIntentResponse(val clientSecret: String = "")

@Serializable
data class ErrorBody(val error: String = "", val message: String = "")
