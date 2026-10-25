package com.vayunmathur.travel.network

/**
 * Booking servicing keyed by booking reference + traveler last name, under
 * `/api/travel` (detail, cancel, change). Split from [TravelApi] so neither
 * object exceeds the TooManyFunctions cap. Shares URL/error helpers via
 * [TravelApiCore.kt].
 */
object TravelOrderApi {

    private const val BASE = TRAVEL_BASE

    private fun enc(s: String): String = travelEnc(s)

    /** Full detail for a booked trip. */
    suspend fun bookingDetail(ref: String, lastName: String): OrderDetailDto =
        travelGetJson(
            "$BASE/bookings/${enc(ref)}?last_name=${enc(lastName)}",
            "Could not load booking"
        )

    /** Preview cancelling a booking (refund, penalty, commitable/message). */
    suspend fun cancelPreview(ref: String, lastName: String): CancellationDto =
        travelPostJson(
            "$BASE/bookings/${enc(ref)}/cancel?last_name=${enc(lastName)}",
            "",
            "Cancellation quote failed"
        )

    /** Commit a cancellation preview. */
    suspend fun confirmCancellation(quoteId: String): CancellationDto =
        travelPostJson(
            "$BASE/cancellations/${enc(quoteId)}/confirm",
            "",
            "Cancellation failed"
        )

    /** Poll a committed cancellation until succeeded/failed. */
    suspend fun cancellationStatus(operationId: String, ref: String, lastName: String): CancellationDto =
        travelGetJson(
            "$BASE/cancellations/${enc(operationId)}?ref=${enc(ref)}&last_name=${enc(lastName)}",
            "Could not load cancellation status"
        )

    /** Start a booking change; returns priced change offers. */
    suspend fun changeRequest(ref: String, lastName: String, input: ChangeRequestInputDto): ChangeRequestResultDto =
        travelPostJson(
            "$BASE/bookings/${enc(ref)}/changes?last_name=${enc(lastName)}",
            input,
            "Change request failed"
        )

    /** Price a change offer (pay / refund / even). */
    suspend fun changePrice(offerId: String, ref: String, lastName: String): ChangePriceDto =
        travelPostJson(
            "$BASE/changes/${enc(offerId)}/price?ref=${enc(ref)}&last_name=${enc(lastName)}",
            "",
            "Could not price change"
        )

    /** Confirm a change offer (may return a checkoutUrl when payment is due). */
    suspend fun confirmChange(offerId: String, ref: String, lastName: String): ChangeConfirmDto =
        travelPostJson(
            "$BASE/changes/${enc(offerId)}/confirm?ref=${enc(ref)}&last_name=${enc(lastName)}",
            "",
            "Change failed"
        )

    /** Live change status (after confirm, while pending). */
    suspend fun changeStatus(ref: String, lastName: String): ChangeStatusDto =
        travelGetJson(
            "$BASE/changes/status?ref=${enc(ref)}&last_name=${enc(lastName)}",
            "Could not load change status"
        )
}
