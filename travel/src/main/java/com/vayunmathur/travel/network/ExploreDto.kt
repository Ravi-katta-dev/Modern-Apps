package com.vayunmathur.travel.network

import kotlinx.serialization.Serializable

/**
 * A destination suggestion from `GET /api/travel/explore` (proxied
 * `find_destination`): cached lowest fare + coordinates for the map pins.
 * Fares are exploration-only — the app re-searches live via [TravelApi.flights]
 * before booking.
 */
@Serializable
data class ExploreDestinationDto(
    val cityName: String = "",
    val iataCode: String = "",
    val imageUrl: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val lowestAmount: String = "",
    val lowestCurrency: String = "",
    /** Airport code of the lowest-fare flight's destination (for live re-search). */
    val destinationCode: String = "",
    /** Departure date of the lowest-fare flight (`YYYY-MM-DD`). */
    val departDate: String = "",
    /** Return date of the lowest-fare flight (`YYYY-MM-DD`, "" when one-way). */
    val returnDate: String = "",
)

/** The result of an explore search: one entry per suggested destination. */
@Serializable
data class ExploreSearchDto(
    val destinations: List<ExploreDestinationDto> = emptyList(),
)
