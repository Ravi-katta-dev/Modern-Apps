package com.vayunmathur.taxi.pure

import com.vayunmathur.taxi.network.lyft.LyftOffersParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

/**
 * Pins fare estimation math through the production coupon pricer:
 * min/max range selection, upfront fallback, first-coupon discounting,
 * clamping at zero and negative-discount rejection.
 *
 * LyftOffersParser.farePrice is internal to the taxi module, so the unit
 * test in the same module calls it directly with a lenient Json config.
 */
class FarePriceTest {
    private val parser = LyftOffersParser(Json { ignoreUnknownKeys = true; isLenient = true })

    @Test
    fun rangeNeedsBothBounds() {
        val fare = parser.farePrice(500L, 900L, null, null, null)!!
        assertEquals(500L, fare.low)
        assertEquals(900L, fare.high)
        assertNull(fare.originalLow)
        assertNull(fare.originalHigh)
    }

    @Test
    fun upfrontStandsAloneAsExactFare() {
        val fare = parser.farePrice(null, null, 750L, null, null)!!
        assertEquals(750L, fare.low)
        assertEquals(750L, fare.high)
        assertNull(fare.originalLow)
    }

    @Test
    fun singleBoundStandsAlone() {
        assertEquals(500L, parser.farePrice(500L, null, null, null, null)!!.low)
        assertEquals(900L, parser.farePrice(null, 900L, null, null, null)!!.high)
    }

    @Test
    fun noPriceAtAllReturnsNull() {
        assertNull(parser.farePrice(null, null, null, null, null))
        assertNull(parser.farePrice(null, null, null, 100L, 100L))
    }

    @Test
    fun couponSubtractsFromBothEndsAndKeepsOriginals() {
        val fare = parser.farePrice(1000L, 1500L, null, 200L, 300L)!!
        assertEquals(800L, fare.low)
        assertEquals(1200L, fare.high)
        assertEquals(1000L, fare.originalLow)
        assertEquals(1500L, fare.originalHigh)
    }

    @Test
    fun zeroDiscountMeansNoOriginals() {
        val fare = parser.farePrice(1000L, 1500L, null, 0L, 0L)!!
        assertEquals(1000L, fare.low)
        assertNull(fare.originalLow)
        assertNull(fare.originalHigh)
    }

    @Test
    fun discountClampsAtZero() {
        val fare = parser.farePrice(300L, 400L, null, 500L, 900L)!!
        assertEquals(0L, fare.low)
        assertEquals(0L, fare.high)
        assertEquals(300L, fare.originalLow)
    }

    @Test
    fun negativeDiscountsAreTreatedAsZero() {
        val fare = parser.farePrice(1000L, 1500L, null, -50L, -70L)!!
        assertEquals(1000L, fare.low)
        assertEquals(1500L, fare.high)
        assertNull(fare.originalLow)
    }

    @Test
    fun upfrontWithCouponDiscountsTheUpfront() {
        val fare = parser.farePrice(null, null, 1200L, 200L, 200L)!!
        assertEquals(1000L, fare.low)
        assertEquals(1000L, fare.high)
        assertEquals(1200L, fare.originalLow)
    }

    @Test
    fun fullDiscountMakesARideFree() {
        val fare = parser.farePrice(800L, 800L, null, 800L, 800L)!!
        assertEquals(0L, fare.low)
        assertEquals(0L, fare.high)
        assertEquals(800L, fare.originalLow)
    }
}
