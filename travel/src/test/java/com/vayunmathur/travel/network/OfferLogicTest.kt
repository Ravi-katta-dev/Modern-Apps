package com.vayunmathur.travel.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Covers pure offer, place and seat helpers in the network DTOs. */
class OfferLogicTest {

    @Test
    fun carryOnLabel() {
        assertEquals("1 carry-on", BaggageDto("carry_on", 1).label)
    }

    @Test
    fun checkedLabel() {
        assertEquals("2 checked", BaggageDto("checked", 2).label)
    }

    @Test
    fun unknownBaggageTypeReplacesUnderscores() {
        assertEquals("3 extra leg", BaggageDto("extra_leg", 3).label)
    }

    @Test
    fun emptyOfferHasNoAirlines() {
        assertEquals(emptyList(), OfferDto().airlineIatas)
    }

    @Test
    fun airlinesAreDistinctAcrossSlices() {
        val first = SliceDto(
            segments = listOf(
                SegmentDto(carrierIata = "BA"),
                SegmentDto(carrierIata = "BA"),
            ),
        )
        val second = SliceDto(
            segments = listOf(
                SegmentDto(carrierIata = "AA"),
                SegmentDto(carrierIata = "BA"),
            ),
        )
        val offer = OfferDto(slices = listOf(first, second))
        assertEquals(listOf("BA", "AA"), offer.airlineIatas)
    }

    @Test
    fun blankAirlineCodesAreFiltered() {
        val slice = SliceDto(
            segments = listOf(
                SegmentDto(carrierIata = ""),
                SegmentDto(carrierIata = "AF"),
            ),
        )
        assertEquals(listOf("AF"), OfferDto(slices = listOf(slice)).airlineIatas)
    }

    @Test
    fun baggageSummaryJoinsFirstSegment() {
        val segment = SegmentDto(
            baggages = listOf(
                BaggageDto("carry_on", 1),
                BaggageDto("checked", 2),
            ),
        )
        val offer = OfferDto(slices = listOf(SliceDto(segments = listOf(segment))))
        assertEquals("1 carry-on · 2 checked", offer.baggageSummary)
    }

    @Test
    fun baggageSummarySkipsZeroQuantities() {
        val segment = SegmentDto(
            baggages = listOf(
                BaggageDto("checked", 0),
                BaggageDto("checked", 2),
            ),
        )
        val offer = OfferDto(slices = listOf(SliceDto(segments = listOf(segment))))
        assertEquals("2 checked", offer.baggageSummary)
    }

    @Test
    fun baggageSummaryEmptyWhenNoSlices() {
        assertEquals("", OfferDto().baggageSummary)
    }

    @Test
    fun baggageSummaryEmptyWhenAllZero() {
        val segment = SegmentDto(baggages = listOf(BaggageDto("checked", 0)))
        val offer = OfferDto(slices = listOf(SliceDto(segments = listOf(segment))))
        assertEquals("", offer.baggageSummary)
    }

    @Test
    fun placeLabelFull() {
        val place = PlaceDto("JFK", "Kennedy", "New York", "United States")
        assertEquals("JFK · Kennedy, New York, United States", place.label)
    }

    @Test
    fun placeLabelCodeOnly() {
        assertEquals("LON", PlaceDto(code = "LON").label)
    }

    @Test
    fun placeLabelSkipsNameMatchingCode() {
        val place = PlaceDto("LON", "LON", "London", "United Kingdom")
        assertEquals("LON, London, United Kingdom", place.label)
    }

    @Test
    fun placeLabelSkipsCityMatchingName() {
        val place = PlaceDto("CDG", "Paris", "Paris", "France")
        assertEquals("CDG · Paris, France", place.label)
    }

    @Test
    fun realSeatsAreDetected() {
        assertTrue(SeatElementDto(type = "seat").isSeat)
        assertTrue(SeatElementDto(type = "restricted_seat_general").isSeat)
    }

    @Test
    fun nonSeatElementsAreRejected() {
        assertFalse(SeatElementDto(type = "empty").isSeat)
        assertFalse(SeatElementDto(type = "lavatory").isSeat)
        assertFalse(SeatElementDto(type = "").isSeat)
    }
}
