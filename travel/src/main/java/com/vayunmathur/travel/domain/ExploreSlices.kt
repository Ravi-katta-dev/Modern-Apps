package com.vayunmathur.travel.domain

/**
 * Live-search slices for an Explore pin: the tapped destination re-searched
 * through the normal flight flow (explore fares are cached estimates, never
 * booked directly). One-way is a single slice; round-trips reverse it.
 * Pure: covered by unit tests.
 */
fun exploreLiveSlices(
    origin: String,
    destinationCode: String,
    departDate: String,
    returnDate: String,
    isRoundTrip: Boolean,
): String =
    if (isRoundTrip && returnDate.isNotBlank()) {
        "$origin:$destinationCode:$departDate,$destinationCode:$origin:$returnDate"
    } else {
        "$origin:$destinationCode:$departDate"
    }
