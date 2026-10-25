package com.vayunmathur.travel.network

import kotlinx.serialization.Serializable

/** Reference data for an airline (name + IATA code), from the offline catalog. */
@Serializable
data class AirlineDto(
    val name: String = "",
    val iataCode: String = "",
    val logoSymbolUrl: String = "",
    val logoLockupUrl: String = "",
)

/** Reference data for an airport, from the offline catalog. */
@Serializable
data class AirportDto(
    val iataCode: String = "",
    val name: String = "",
    val cityName: String = "",
    val iataCountryCode: String = "",
    val timeZone: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/** Reference data for an aircraft type, from the offline catalog. */
@Serializable
data class AircraftDto(
    val id: String = "",
    val name: String = "",
    val iataCode: String = "",
)

/** Reference data for a city, from the offline catalog. */
@Serializable
data class CityDto(
    val name: String = "",
    val iataCode: String = "",
    val iataCountryCode: String = "",
)
