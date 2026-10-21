package com.vayunmathur.travel.network

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun nightsBetween(checkInIso: String, checkOutIso: String): Long =
    ChronoUnit.DAYS.between(LocalDate.parse(checkInIso), LocalDate.parse(checkOutIso))

private fun amountOrNull(raw: String): BigDecimal? = runCatching { BigDecimal(raw) }.getOrNull()

private fun cheapestRate(rates: List<StayRateDto>): StayRateDto? =
    rates.filter { amountOrNull(it.totalAmount) != null }.minByOrNull { BigDecimal(it.totalAmount) }

private fun perNightAverage(totalAmount: String, nights: Long): BigDecimal? {
    if (nights <= 0) return null
    val total = amountOrNull(totalAmount) ?: return null
    return total.divide(BigDecimal.valueOf(nights), 2, RoundingMode.HALF_UP)
}

private fun summedTotal(amounts: List<String>): BigDecimal =
    amounts.fold(BigDecimal.ZERO) { acc, raw -> acc + (amountOrNull(raw) ?: BigDecimal.ZERO) }

/** Covers stay schedule and expense math over pure DTOs. */
class StayExpenseTest {

    @Test
    fun oneNightStay() {
        assertEquals(1, nightsBetween("2026-10-02", "2026-10-03"))
    }

    @Test
    fun weekStayCountsSevenNights() {
        assertEquals(7, nightsBetween("2026-10-02", "2026-10-09"))
    }

    @Test
    fun sameDayIsZeroNights() {
        assertEquals(0, nightsBetween("2026-10-02", "2026-10-02"))
    }

    @Test
    fun checkoutBeforeCheckinIsNegative() {
        val nights = nightsBetween("2026-10-05", "2026-10-02")
        assertTrue(nights < 0, "expected negative nights, was $nights")
    }

    @Test
    fun leapDayCountsExtraNight() {
        assertEquals(2, nightsBetween("2024-02-28", "2024-03-01"))
    }

    @Test
    fun yearBoundaryCountsNights() {
        assertEquals(2, nightsBetween("2026-12-31", "2027-01-02"))
    }

    @Test
    fun cheapestRatePicksLowest() {
        val rates = listOf(
            StayRateDto(totalAmount = "200"),
            StayRateDto(totalAmount = "150.50"),
            StayRateDto(totalAmount = "300"),
        )
        assertEquals("150.50", cheapestRate(rates)?.totalAmount)
    }

    @Test
    fun cheapestRateEmptyIsNull() {
        assertEquals(null, cheapestRate(emptyList()))
    }

    @Test
    fun cheapestRateSkipsUnparseable() {
        val rates = listOf(StayRateDto(totalAmount = "oops"), StayRateDto(totalAmount = "120"))
        assertEquals("120", cheapestRate(rates)?.totalAmount)
    }

    @Test
    fun cheapestRateAllInvalidIsNull() {
        val rates = listOf(StayRateDto(totalAmount = "oops"), StayRateDto(totalAmount = ""))
        assertEquals(null, cheapestRate(rates))
    }

    @Test
    fun perNightAverageDividesTotal() {
        assertEquals(BigDecimal("100.00"), perNightAverage("300", 3))
    }

    @Test
    fun perNightAverageZeroNightsIsNull() {
        assertEquals(null, perNightAverage("300", 0))
    }

    @Test
    fun perNightAverageInvalidTotalIsNull() {
        assertEquals(null, perNightAverage("oops", 2))
    }

    @Test
    fun summedTotalAddsValidAndSkipsInvalid() {
        assertEquals(BigDecimal("300.50"), summedTotal(listOf("100.50", "200", "oops")))
    }

    @Test
    fun summedTotalEmptyIsZero() {
        assertEquals(BigDecimal.ZERO, summedTotal(emptyList()))
    }
}
