package com.vayunmathur.travel.pure

import com.vayunmathur.travel.domain.exploreLiveSlices
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the live-search slices built for an Explore pin tap: explore fares
 * are cached estimates, so the sheet re-searches the tapped destination
 * through the normal flight flow before anything is bookable.
 */
class ExploreSlicesTest {
    @Test
    fun oneWayIsSingleSlice() {
        assertEquals(
            "SFO:CDG:2026-09-23",
            exploreLiveSlices(
                origin = "SFO",
                destinationCode = "CDG",
                departDate = "2026-09-23",
                returnDate = "",
                isRoundTrip = false,
            )
        )
    }

    @Test
    fun roundTripReversesSecondSlice() {
        assertEquals(
            "SFO:CDG:2026-09-23,CDG:SFO:2026-09-30",
            exploreLiveSlices(
                origin = "SFO",
                destinationCode = "CDG",
                departDate = "2026-09-23",
                returnDate = "2026-09-30",
                isRoundTrip = true,
            )
        )
    }

    @Test
    fun roundTripWithoutReturnFallsBackToOneWay() {
        assertEquals(
            "SFO:CDG:2026-09-23",
            exploreLiveSlices(
                origin = "SFO",
                destinationCode = "CDG",
                departDate = "2026-09-23",
                returnDate = "",
                isRoundTrip = true,
            )
        )
    }
}
