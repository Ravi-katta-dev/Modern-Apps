package com.vayunmathur.travel.ui

/** Passenger mix edited in the popup dialog (mirrors the flight query fields). */
data class PassengerMix(
    val adults: Int = 1,
    val childAges: List<Int> = emptyList(),
    val infantsInSeat: Int = 0,
    val infantsOnLap: Int = 0,
) {
    /** Total headcount label, e.g. "3". */
    val total: Int get() = adults + childAges.size + infantsInSeat + infantsOnLap
}
