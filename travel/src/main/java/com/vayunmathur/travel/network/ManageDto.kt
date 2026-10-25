package com.vayunmathur.travel.network

import kotlinx.serialization.Serializable

/**
 * A cancel preview (`POST /api/travel/bookings/:ref/cancel`) or the committed
 * operation (`POST /cancellations/:quoteId/confirm`, polled via
 * `GET /cancellations/:operationId`). Cached server-side by id.
 */
@Serializable
data class CancellationDto(
    val id: String = "",
    val orderId: String = "",
    val refundAmount: String = "0",
    val refundCurrency: String = "USD",
    val refundTo: String = "",
    val penaltyAmount: String? = null,
    val commitable: Boolean = false,
    val status: String = "",
    val message: String? = null,
)

/** One priced alternative when changing a booking. */
@Serializable
data class ChangeOfferDto(
    val id: String = "",
    val changeTotalAmount: String = "",
    val changeTotalCurrency: String = "",
    val newTotalAmount: String = "",
    val newTotalCurrency: String = "",
    val slices: List<SliceDto> = emptyList(),
    val description: String = "",
)

/** Result of starting a change: the booking ref + priced offers. */
@Serializable
data class ChangeRequestResultDto(
    val id: String = "",
    val offers: List<ChangeOfferDto> = emptyList(),
)

/** A slice to add during a change (origin/destination/date). */
@Serializable
data class SearchSliceInputDto(
    val origin: String = "",
    val destination: String = "",
    val date: String = "",
)

/** Body for starting a booking change. */
@Serializable
data class ChangeRequestInputDto(
    val orderId: String = "",
    val removeSliceIds: List<String> = emptyList(),
    val add: List<SearchSliceInputDto> = emptyList(),
    val cabin: String? = null,
)

/** Price outcome for `POST /changes/:offerId/price`. */
@Serializable
data class ChangePriceDto(
    val outcome: String = "",
    val amount: String = "0",
    val currency: String = "USD",
    val newFareAmount: String = "",
)

/** Result of `POST /changes/:offerId/confirm`. */
@Serializable
data class ChangeConfirmDto(
    val orderId: String = "",
    val status: String = "",
    val checkoutUrl: String? = null,
)

/** Live status for `GET /changes/status`. */
@Serializable
data class ChangeStatusDto(
    val orderId: String = "",
    val status: String = "",
    val checkoutUrl: String? = null,
)
