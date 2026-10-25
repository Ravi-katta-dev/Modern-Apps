package com.vayunmathur.travel.util

import androidx.lifecycle.viewModelScope
import com.vayunmathur.travel.data.AirportCatalog
import com.vayunmathur.travel.data.RecentSearch
import com.vayunmathur.travel.data.Vertical
import com.vayunmathur.travel.domain.faresForItineraryPair
import com.vayunmathur.travel.domain.groupInboundSlices
import com.vayunmathur.travel.domain.groupOutboundSlices
import com.vayunmathur.travel.domain.roundTripOffers
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.network.PlaceDto
import com.vayunmathur.travel.network.TravelApi
import kotlinx.coroutines.launch

/** Airport/city suggestions for [query] from the offline catalog; never throws. */
suspend fun TravelViewModel.autocomplete(query: String): List<PlaceDto> {
    AirportCatalog.ensureLoaded(getApplication())
    return runCatching { AirportCatalog.search(query) }.getOrDefault(emptyList())
}

/** Parsed first-leg details used for the recent-search label. */
private data class FirstLeg(val origin: String, val destination: String, val depart: String)

private fun parseFirstLeg(slices: String): FirstLeg {
    val parts = slices.substringBefore(',').split(':')
    return FirstLeg(
        origin = parts.getOrNull(0).orEmpty(),
        destination = parts.getOrNull(1).orEmpty(),
        depart = parts.getOrNull(2).orEmpty()
    )
}

private fun TravelViewModel.recordFlightSearch(query: FlightQuery, leg: FirstLeg) {
    val multiCity = query.legs.size > 2 ||
        (query.legs.size == 2 && !query.isRoundTrip)
    recordRecent(
        RecentSearch(
            vertical = Vertical.FLIGHTS.name,
            label = if (multiCity) {
                "${leg.origin} → … · multi-city"
            } else {
                "${leg.origin} → ${leg.destination} · ${TravelViewModel.prettyDate(leg.depart)}"
            },
            origin = leg.origin,
            destination = leg.destination,
            depart = leg.depart,
            returnDate = null,
            adults = query.adults,
            cabin = query.cabin,
        )
    )
}

private suspend fun TravelViewModel.loyaltyParam(): String =
    repository.getAllFrequentFlyers()
        .joinToString(",") { "${it.airlineIata}:${it.accountNumber}" }

/**
 * Synchronous flight search: one `GET /flights` returns every offer;
 * sorting/filtering is on-device ([sortOffers][com.vayunmathur.travel.domain.sortOffers]).
 */
fun TravelViewModel.searchFlights(query: FlightQuery) {
    currentQuery = query
    recordFlightSearch(query, parseFirstLeg(query.slices))
    flightsMutable.value = FlightResultsState(loading = true, hasSearched = true)
    viewModelScope.launch {
        val loyalty = loyaltyParam()
        runCatching {
            TravelApi.flights(
                slices = query.slices,
                adults = query.adults,
                children = query.children,
                infants = query.infants,
                cabin = query.cabin,
                maxConnections = query.maxConnections,
                loyalty = loyalty,
            )
        }
            .onSuccess { result ->
                flightsMutable.value = FlightResultsState(
                    hasSearched = true,
                    offerRequestId = result.offerRequestId,
                    allOffers = result.offers,
                )
            }
            .onFailure {
                flightsMutable.value = FlightResultsState(error = errorMessage(it), hasSearched = true)
            }
    }
}

/** Change the on-device sort (no re-fetch). */
fun TravelViewModel.setSort(sort: OfferSort) {
    flightsMutable.value = flightsMutable.value.copy(sort = sort)
}

fun TravelViewModel.setMaxStopsFilter(maxStops: Int?) {
    flightsMutable.value = flightsMutable.value.copy(
        filters = flightsMutable.value.filters.copy(maxStops = maxStops),
    )
}

fun TravelViewModel.toggleAirlineFilter(iata: String) {
    val current = flightsMutable.value.filters.airlines
    val next = if (iata in current) current - iata else current + iata
    flightsMutable.value = flightsMutable.value.copy(filters = flightsMutable.value.filters.copy(airlines = next))
}

fun TravelViewModel.setFareBrandFilter(fareBrand: String?) {
    flightsMutable.value = flightsMutable.value.copy(
        filters = flightsMutable.value.filters.copy(fareBrand = fareBrand),
    )
}

fun TravelViewModel.clearFilters() {
    flightsMutable.value = flightsMutable.value.copy(filters = FlightFilters())
}

/** Seed the review screen with the tapped offer; clear any prior ancillaries. */
fun TravelViewModel.selectOffer(offer: OfferDto) {
    reviewMutable.value = OfferReviewState(offer = offer)
    selectedOfferIdsMutable.value = listOf(offer.offerId)
    selectedBaggageMutable.value = emptyMap()
    selectedExtrasMutable.value = emptyMap()
    selectedSeatsMutable.value = emptyMap()
    seatMapMutable.value = SeatMapState()
    ancillariesMutable.value = AncillariesState()
    currentTrip = null
    bookingMutable.value = BookingState.Idle
    checkoutMutable.value = CheckoutState.Idle
    tripPollMutable.value = TripPollState.Idle
}

/**
 * Seed the review screen with several offers (multi-city: one per leg);
 * booking posts all ids in one trip.
 */
fun TravelViewModel.selectOffers(offers: List<OfferDto>) {
    val first = offers.firstOrNull()
    reviewMutable.value = OfferReviewState(offer = first)
    selectedOfferIdsMutable.value = offers.map { it.offerId }
    selectedBaggageMutable.value = emptyMap()
    selectedExtrasMutable.value = emptyMap()
    selectedSeatsMutable.value = emptyMap()
    seatMapMutable.value = SeatMapState()
    ancillariesMutable.value = AncillariesState()
    currentTrip = null
    bookingMutable.value = BookingState.Idle
    checkoutMutable.value = CheckoutState.Idle
    tripPollMutable.value = TripPollState.Idle
}

/**
 * Re-read the chosen offer right before booking (offers expire) — and the
 * only server call the review screen needs (process-death restore).
 */
fun TravelViewModel.refreshOffer(offerId: String) {
    reviewMutable.value = reviewMutable.value.copy(loading = true, error = null)
    viewModelScope.launch {
        runCatching { TravelApi.offer(offerId) }
            .onSuccess { reviewMutable.value = OfferReviewState(offer = it) }
            .onFailure {
                // Keep the previously selected offer so the user can still proceed.
                reviewMutable.value = reviewMutable.value.copy(loading = false, error = errorMessage(it))
            }
    }
}

/**
 * Start the round-trip partial flow: one synchronous search, then group
 * on-device (outbound slices first).
 */
fun TravelViewModel.startPartialSearch(query: FlightQuery) {
    partialFlowMutable.value = PartialFlowState(loading = true)
    viewModelScope.launch {
        val loyalty = loyaltyParam()
        runCatching {
            TravelApi.flights(
                slices = query.slices,
                adults = query.adults,
                children = query.children,
                infants = query.infants,
                cabin = query.cabin,
                maxConnections = query.maxConnections,
                loyalty = loyalty,
            )
        }
            .onSuccess { result ->
                val offers = roundTripOffers(result.offers)
                partialFlowMutable.value = PartialFlowState(
                    allOffers = offers,
                    step = 0,
                    offers = groupOutboundSlices(offers),
                )
            }
            .onFailure { partialFlowMutable.value = PartialFlowState(error = errorMessage(it)) }
    }
}

/** Round-trip step 2: inbound slices for the chosen outbound signature. */
fun TravelViewModel.loadPartialReturn(outboundSignature: String) {
    val all = partialFlowMutable.value.allOffers
    if (all.isEmpty()) {
        partialFlowMutable.value = partialFlowMutable.value.copy(error = "Please restart your search.")
        return
    }
    partialFlowMutable.value = partialFlowMutable.value.copy(
        loading = false,
        error = null,
        step = 1,
        outboundSignature = outboundSignature,
        offers = groupInboundSlices(all, outboundSignature),
    )
}

/** Round-trip step 3: fares for the chosen outbound+inbound pair. */
fun TravelViewModel.loadPartialFares(outboundSignature: String, inboundSignature: String) {
    val all = partialFlowMutable.value.allOffers
    if (all.isEmpty()) {
        partialFlowMutable.value = partialFlowMutable.value.copy(error = "Please restart your search.")
        return
    }
    partialFlowMutable.value = partialFlowMutable.value.copy(
        loading = false,
        error = null,
        step = 2,
        outboundSignature = outboundSignature,
        inboundSignature = inboundSignature,
        offers = faresForItineraryPair(all, outboundSignature, inboundSignature),
    )
}

/** Look up a partial-flow offer by id (for seeding the review screen). */
fun TravelViewModel.partialOfferById(offerId: String): OfferDto? =
    partialFlowMutable.value.allOffers.find { it.offerId == offerId }
        ?: partialFlowMutable.value.offers.find { it.offerId == offerId }

// --- Multi-city: one one-way search per leg --------------------------------

/** Start the multi-city flow: search the first leg (each leg is one-way). */
fun TravelViewModel.startMultiCitySearch(query: FlightQuery) {
    multiCityMutable.value = MultiCityState(loading = true)
    searchMultiCityLeg(query, 0)
}

/** Search one leg of a multi-city trip as a one-way. */
fun TravelViewModel.searchMultiCityLeg(query: FlightQuery, legIndex: Int) {
    val legs = query.legs
    val leg = legs.getOrNull(legIndex)
    if (leg == null) {
        multiCityMutable.value = multiCityMutable.value.copy(
            loading = false,
            error = "Unknown leg. Please restart your search."
        )
        return
    }
    currentQuery = query
    multiCityMutable.value = multiCityMutable.value.copy(loading = true, error = null)
    viewModelScope.launch {
        val loyalty = loyaltyParam()
        val parts = leg.split(':')
        runCatching {
            TravelApi.flights(
                slices = leg,
                adults = query.adults,
                children = query.children,
                infants = query.infants,
                cabin = query.cabin,
                maxConnections = query.maxConnections,
                loyalty = loyalty,
            )
        }
            .onSuccess { result ->
                multiCityMutable.value = multiCityMutable.value.copy(
                    loading = false,
                    legOffers = multiCityMutable.value.legOffers + (legIndex to result.offers),
                )
            }
            .onFailure {
                multiCityMutable.value = multiCityMutable.value.copy(
                    loading = false,
                    error = errorMessage(it) + " (${parts.getOrNull(0).orEmpty()} → ${parts.getOrNull(1).orEmpty()})"
                )
            }
    }
}

/** Pick one offer for a multi-city leg. */
fun TravelViewModel.selectMultiCityOffer(legIndex: Int, offer: OfferDto) {
    multiCityMutable.value = multiCityMutable.value.copy(
        selectedOfferIds = multiCityMutable.value.selectedOfferIds + (legIndex to offer.offerId),
    )
}

/** The chosen offer per leg, in leg order (nulls where unpicked). */
fun TravelViewModel.multiCitySelection(legCount: Int): List<OfferDto?> =
    (0 until legCount).map { legIndex ->
        val id = multiCityMutable.value.selectedOfferIds[legIndex] ?: return@map null
        multiCityMutable.value.legOffers[legIndex]?.find { it.offerId == id }
    }
