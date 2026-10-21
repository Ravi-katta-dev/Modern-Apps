package com.vayunmathur.taxi.pure

import com.vayunmathur.taxi.data.Provider
import com.vayunmathur.taxi.data.RideQuote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins quote selection and fare formatting: cheapest-quote wins, range
 * versus exact display, struck-through originals, and the estimate
 * result contract (unavailable carries an empty fare and eta -1).
 *
 * Mirrors ui/RideResults' sort and formatFare/formatOriginalFare plus
 * intents/RideEstimateIntent's best-quote/formatFare rules with plain
 * data so the host JVM can run them.
 */
class QuoteSelectFormatTest {
    private fun quote(
        id: String,
        low: Long,
        high: Long = low,
        eta: Int? = 4,
        origLow: Long? = null,
        origHigh: Long? = null,
    ): RideQuote = RideQuote(
        provider = Provider.LYFT, productId = id, displayName = id,
        fareLowMinor = low, fareHighMinor = high, currency = "USD",
        pickupEtaMinutes = eta, tripDurationMinutes = 18,
        surgeMultiplier = null, capacity = 4,
        originalFareLowMinor = origLow, originalFareHighMinor = origHigh,
    )

    private fun best(quotes: List<RideQuote>): RideQuote? = quotes.minByOrNull { it.fareLowMinor }

    private fun money(minor: Long): String = "$%.2f".format(minor / 100.0)

    private fun estimateResult(quotes: List<RideQuote>): Triple<Boolean, String, Int> {
        val quote = best(quotes)
        return if (quote != null) {
            Triple(true, formatFare(quote), quote.pickupEtaMinutes ?: -1)
        } else {
            Triple(false, "", -1)
        }
    }

    private fun formatFare(q: RideQuote): String =
        if (q.isRange) "${money(q.fareLowMinor)} – ${money(q.fareHighMinor)}" else money(q.fareLowMinor)

    private fun formatOriginal(q: RideQuote): String {
        val low = q.originalFareLowMinor ?: q.fareLowMinor
        val high = q.originalFareHighMinor ?: q.fareHighMinor
        return if (low != high) "${money(low)} – ${money(high)}" else money(low)
    }

    @Test
    fun cheapestLowFareWins() {
        val quotes = listOf(quote("a", 900L), quote("b", 500L), quote("c", 700L))
        assertEquals("b", best(quotes)?.productId)
    }

    @Test
    fun emptyQuoteListHasNoBest() {
        assertNull(best(emptyList()))
    }

    @Test
    fun tieKeepsFirstCheapest() {
        val quotes = listOf(quote("a", 500L), quote("b", 500L))
        assertEquals("a", best(quotes)?.productId)
    }

    @Test
    fun exactFareFormatsToDollarsAndCents() {
        assertEquals("$5.00", formatFare(quote("a", 500L)))
        assertEquals("$5.05", formatFare(quote("a", 505L)))
    }

    @Test
    fun rangeFareJoinsLowAndHigh() {
        assertEquals("$5.00 – $9.00", formatFare(quote("a", 500L, 900L)))
    }

    @Test
    fun zeroFareFormatsCleanly() {
        assertEquals("$0.00", formatFare(quote("a", 0L)))
    }

    @Test
    fun largeFareHasNoThousandsSeparator() {
        assertEquals("$1234.56", formatFare(quote("a", 123456L)))
    }

    @Test
    fun originalFallsBackToCurrentFare() {
        assertEquals("$5.00", formatOriginal(quote("a", 500L)))
        assertEquals("$5.00 – $9.00", formatOriginal(quote("a", 500L, 900L)))
    }

    @Test
    fun originalRangeFormatsWhenDiscounted() {
        assertEquals("$9.00 – $11.00", formatOriginal(quote("a", 500L, 700L, origLow = 900L, origHigh = 1100L)))
    }

    @Test
    fun unavailableEstimateCarriesEmptyFareAndMinusOneEta() {
        assertEquals(Triple(true, "$5.00", 4), estimateResult(listOf(quote("a", 500L))))
        assertEquals(Triple(true, "$5.00", -1), estimateResult(listOf(quote("a", 500L, eta = null))))
        assertEquals(Triple(false, "", -1), estimateResult(emptyList()))
    }
}
