package com.vayunmathur.travel.ui

/**
 * Explore search params carried by `Route.Explore`: origin, trip shape,
 * dates, cabin and party. The Explore page fetches destinations via
 * `TravelApi.explore`; nothing here is bookable (pin sheet re-searches live).
 */
data class ExploreRequest(
    val origin: String,
    val tripType: String,
    val depart: String,
    val returnDate: String? = null,
    val cabin: String = "economy",
    val adults: Int = 1,
    val children: String = "",
    val infants: Int = 0,
    val maxConnections: Int = -1,
)
