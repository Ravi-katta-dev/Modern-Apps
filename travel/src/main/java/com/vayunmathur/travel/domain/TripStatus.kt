package com.vayunmathur.travel.domain

import com.vayunmathur.travel.R
import com.vayunmathur.travel.data.TRIP_STATUS_CANCELLED
import com.vayunmathur.travel.data.TRIP_STATUS_CONFIRMED
import com.vayunmathur.travel.data.TRIP_STATUS_FAILED
import com.vayunmathur.travel.data.TRIP_STATUS_PARTIAL
import com.vayunmathur.travel.data.TRIP_STATUS_PENDING_PAYMENT
import com.vayunmathur.travel.data.TRIP_STATUS_PROCESSING

/**
 * Trip status mapping + labels for the confirmation / trips UI. Pure: covered
 * by unit tests. Unknown statuses fall back to a capitalized raw string so the
 * UI never blanks on a new server status.
 */

/** Server trip statuses that keep polling (payment or processing not final). */
fun isTripPollingStatus(status: String): Boolean =
    status == TRIP_STATUS_PENDING_PAYMENT || status == TRIP_STATUS_PROCESSING

/** Statuses where the "Complete payment" button applies (checkout not done). */
fun isTripAwaitingPayment(status: String): Boolean = status == TRIP_STATUS_PENDING_PAYMENT

/** Whether [checkoutExpiresAt] (ISO instant, blank = unknown) is still in the future. */
fun isCheckoutUnexpired(checkoutExpiresAt: String): Boolean {
    if (checkoutExpiresAt.isBlank()) return true
    return runCatching {
        val now = kotlin.time.Clock.System.now()
        kotlin.time.Instant.parse(checkoutExpiresAt) > now
    }.getOrDefault(true)
}

/** String resource for the status chip/title of [status]. */
fun tripStatusLabel(status: String): Int = when (status) {
    TRIP_STATUS_PENDING_PAYMENT -> R.string.status_payment_pending
    TRIP_STATUS_PROCESSING -> R.string.status_processing
    TRIP_STATUS_CONFIRMED -> R.string.status_confirmed
    TRIP_STATUS_PARTIAL -> R.string.status_partial
    TRIP_STATUS_FAILED -> R.string.status_failed
    TRIP_STATUS_CANCELLED -> R.string.status_cancelled
    else -> R.string.status_unknown
}

/** Display text for [status] when no string resource fits (legacy local status). */
fun tripStatusDisplay(status: String): String = when (status) {
    TRIP_STATUS_PENDING_PAYMENT -> "Payment pending"
    TRIP_STATUS_PROCESSING -> "Processing"
    TRIP_STATUS_CONFIRMED -> "Confirmed"
    TRIP_STATUS_PARTIAL -> "Partial"
    TRIP_STATUS_FAILED -> "Failed"
    TRIP_STATUS_CANCELLED -> "Cancelled"
    else -> status.replaceFirstChar { it.uppercase() }.ifBlank { "Unknown" }
}
