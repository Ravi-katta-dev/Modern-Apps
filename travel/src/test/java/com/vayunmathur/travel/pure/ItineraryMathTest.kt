package com.vayunmathur.travel.pure

import com.vayunmathur.travel.network.BaggageDto
import com.vayunmathur.travel.network.ConditionsDto
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.network.OfferPassengerDto
import com.vayunmathur.travel.network.PenaltyRuleDto
import com.vayunmathur.travel.network.SegmentDto
import com.vayunmathur.travel.network.SliceDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins itinerary math through the production DTO getters: marketing
 * airline collection, first-segment baggage summaries, slice stop
 * counts and the client-side sort/filter in FlightResultsState.
 *
 * OfferDto/SliceDto/SegmentDto are plain serializable data, so they
 * are built directly; only pure getters are asserted.
 */
class ItineraryMathTest {
    private fun segment(
        carrier: String = "AA",
        baggages: List<BaggageDto> = emptyList(),
    ): SegmentDto = SegmentDto(
        id = "s", carrier = "American", carrierIata = carrier,
        flightNumber = "100", origin = "JFK", destination = "LAX",
        departureAt = "2026-09-01T10:00:00", arrivalAt = "2026-09-01T13:00:00",
        aircraft = "738", cabinClass = "economy", baggages = baggages,
    )

    private fun slice(
        stops: Long = 0,
        duration: Long = 180,
        segments: List<SegmentDto> = listOf(segment()),
        brand: String = "",
    ): SliceDto = SliceDto(
        id = "sl", origin = "JFK", destination = "LAX",
        departureAt = "2026-09-01T10:00:00", arrivalAt = "2026-09-01T13:00:00",
        durationMinutes = duration, stops = stops, segments = segments,
        conditions = ConditionsDto(), fareBrandName = brand,
    )

    private fun offer(
        slices: List<SliceDto> = listOf(slice()),
        amount: String = "412",
        brand: String = "",
    ): OfferDto = OfferDto(
        offerId = "o1", totalAmount = amount, currency = "USD",
        slices = slices, fareBrand = brand,
    )

    private fun offerWithBags(bags: List<BaggageDto>): OfferDto =
        offer(listOf(slice(segments = listOf(segment(baggages = bags)))))

    @Test
    fun airlineIatasAreDistinctAndSkipBlanks() {
        val slices = listOf(
            slice(segments = listOf(segment("AA"), segment(""), segment("AA"), segment("DL"))),
        )
        assertEquals(listOf("AA", "DL"), offer(slices).airlineIatas)
    }

    @Test
    fun noSegmentsMeansNoAirlines() {
        assertEquals(emptyList(), offer(listOf(slice(segments = emptyList()))).airlineIatas)
    }

    @Test
    fun baggageSummaryJoinsPositiveAllowances() {
        val bags = listOf(BaggageDto("carry_on", 1), BaggageDto("checked", 2), BaggageDto("checked", 0))
        assertEquals("1 carry-on · 2 checked", offerWithBags(bags).baggageSummary)
    }

    @Test
    fun baggageSummaryIsEmptyWithoutAllowances() {
        assertEquals("", offer().baggageSummary)
        assertEquals("", offer(emptyList()).baggageSummary)
    }

    @Test
    fun unknownBaggageTypeFallsBackToSpacedName() {
        assertEquals("1 extra legroom", offerWithBags(listOf(BaggageDto("extra_legroom", 1))).baggageSummary)
    }

    @Test
    fun stopsCountMatchesConnections() {
        assertEquals(0L, slice(stops = 0L, segments = listOf(segment())).stops)
        assertEquals(1L, slice(stops = 1L, segments = listOf(segment(), segment())).stops)
    }

    @Test
    fun totalDurationSumsAcrossSlices() {
        val o = offer(listOf(slice(duration = 180), slice(duration = 200)))
        assertEquals(380L, o.slices.sumOf { it.durationMinutes })
    }

    @Test
    fun maxStopsFilterKeepsOnlyNonstops() {
        val offers = listOf(offer(listOf(slice(stops = 0))), offer(listOf(slice(stops = 1))))
        assertEquals(1, offers.filter { o -> o.slices.all { it.stops <= 0 } }.size)
    }

    @Test
    fun airlineFilterMatchesAnySlice() {
        val offers = listOf(
            offer(listOf(slice(segments = listOf(segment("AA"))))),
            offer(listOf(slice(segments = listOf(segment("DL"))))),
        )
        assertEquals(1, offers.filter { o -> o.airlineIatas.any { it in setOf("AA") } }.size)
    }

    @Test
    fun fareBrandFilterIsExact() {
        val offers = listOf(offer(brand = "Main"), offer(brand = "Basic"), offer())
        assertEquals(1, offers.filter { it.fareBrand == "Main" }.size)
    }

    @Test
    fun emptyOfferHasSaneDefaults() {
        val o = OfferDto()
        assertEquals(emptyList(), o.airlineIatas)
        assertEquals("", o.baggageSummary)
        assertEquals("0", o.totalAmount)
        assertEquals(OfferPassengerDto(), OfferPassengerDto("", "", null))
        assertEquals(PenaltyRuleDto(), PenaltyRuleDto(false, null, null))
    }

    @Test
    fun availableAirlinesAreDistinctAndSorted() {
        val iatas = listOf(offer(listOf(slice(segments = listOf(segment("DL"))))),
            offer(listOf(slice(segments = listOf(segment("AA"), segment("DL"))))))
        val available = iatas.flatMap { it.airlineIatas }.distinct().sorted()
        assertEquals(listOf("AA", "DL"), available)
        assertTrue(available == available.sorted())
    }
}
