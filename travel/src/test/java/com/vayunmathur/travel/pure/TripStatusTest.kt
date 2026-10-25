package com.vayunmathur.travel.pure

import com.vayunmathur.travel.R
import com.vayunmathur.travel.data.TRIP_STATUS_CANCELLED
import com.vayunmathur.travel.data.TRIP_STATUS_CONFIRMED
import com.vayunmathur.travel.data.TRIP_STATUS_FAILED
import com.vayunmathur.travel.data.TRIP_STATUS_PARTIAL
import com.vayunmathur.travel.data.TRIP_STATUS_PENDING_PAYMENT
import com.vayunmathur.travel.data.TRIP_STATUS_PROCESSING
import com.vayunmathur.travel.domain.isCheckoutUnexpired
import com.vayunmathur.travel.domain.isTripAwaitingPayment
import com.vayunmathur.travel.domain.isTripPollingStatus
import com.vayunmathur.travel.domain.tripStatusDisplay
import com.vayunmathur.travel.domain.tripStatusLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins trip status mapping/labels: which statuses keep polling, which show
 * "Complete payment", checkout-expiry gating, and the status labels.
 */
class TripStatusTest {

    @Test
    fun pendingAndProcessingKeepPolling() {
        assertTrue(isTripPollingStatus(TRIP_STATUS_PENDING_PAYMENT))
        assertTrue(isTripPollingStatus(TRIP_STATUS_PROCESSING))
    }

    @Test
    fun finalStatusesStopPolling() {
        assertFalse(isTripPollingStatus(TRIP_STATUS_CONFIRMED))
        assertFalse(isTripPollingStatus(TRIP_STATUS_PARTIAL))
        assertFalse(isTripPollingStatus(TRIP_STATUS_FAILED))
        assertFalse(isTripPollingStatus(TRIP_STATUS_CANCELLED))
    }

    @Test
    fun onlyPendingPaymentShowsCompletePayment() {
        assertTrue(isTripAwaitingPayment(TRIP_STATUS_PENDING_PAYMENT))
        assertFalse(isTripAwaitingPayment(TRIP_STATUS_PROCESSING))
        assertFalse(isTripAwaitingPayment(TRIP_STATUS_CONFIRMED))
    }

    @Test
    fun blankExpiryMeansUnexpired() {
        assertTrue(isCheckoutUnexpired(""))
    }

    @Test
    fun futureExpiryIsUnexpired() {
        assertTrue(isCheckoutUnexpired("2999-01-01T00:00:00Z"))
    }

    @Test
    fun pastExpiryIsExpired() {
        assertFalse(isCheckoutUnexpired("2000-01-01T00:00:00Z"))
    }

    @Test
    fun unparseableExpiryIsUnexpired() {
        assertTrue(isCheckoutUnexpired("not-a-date"))
    }

    @Test
    fun statusLabelsMapToResources() {
        assertEquals(R.string.status_payment_pending, tripStatusLabel(TRIP_STATUS_PENDING_PAYMENT))
        assertEquals(R.string.status_processing, tripStatusLabel(TRIP_STATUS_PROCESSING))
        assertEquals(R.string.status_confirmed, tripStatusLabel(TRIP_STATUS_CONFIRMED))
        assertEquals(R.string.status_partial, tripStatusLabel(TRIP_STATUS_PARTIAL))
        assertEquals(R.string.status_failed, tripStatusLabel(TRIP_STATUS_FAILED))
        assertEquals(R.string.status_cancelled, tripStatusLabel(TRIP_STATUS_CANCELLED))
        assertEquals(R.string.status_unknown, tripStatusLabel("weird"))
    }

    @Test
    fun statusDisplayFallsBackToCapitalized() {
        assertEquals("Payment pending", tripStatusDisplay(TRIP_STATUS_PENDING_PAYMENT))
        assertEquals("Confirmed", tripStatusDisplay(TRIP_STATUS_CONFIRMED))
        assertEquals("On hold", tripStatusDisplay("on hold"))
        assertEquals("Unknown", tripStatusDisplay(""))
    }
}
