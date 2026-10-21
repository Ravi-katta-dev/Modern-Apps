package com.vayunmathur.travel.ui

import com.vayunmathur.travel.network.ConditionsDto
import com.vayunmathur.travel.network.PenaltyRuleDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Covers the pure fare and schedule labels in TravelFormat. */
class TravelFormatTest {

    @Test
    fun wholeDollarsDropDecimals() {
        assertEquals("$412", formatMoney("412", "USD"))
    }

    @Test
    fun lowercaseCurrencyStillMapsSymbol() {
        assertEquals("$412", formatMoney("412", "usd"))
    }

    @Test
    fun wholePoundsDropDecimals() {
        assertEquals("£220", formatMoney("220.00", "GBP"))
    }

    @Test
    fun unknownCurrencyPrefixesCode() {
        assertEquals("JPY 412", formatMoney("412", "JPY"))
    }

    @Test
    fun fractionalEurosKeepSymbolAndAmount() {
        val label = formatMoney("220.50", "EUR")
        assertTrue(label.startsWith("€"), "expected euro symbol, was $label")
        assertTrue("220" in label, "expected amount digits, was $label")
    }

    @Test
    fun zeroAmountFormats() {
        assertEquals("$0", formatMoney("0", "USD"))
    }

    @Test
    fun unparseableAmountFallsBackToRaw() {
        assertEquals("USD abc", formatMoney("abc", "USD"))
    }

    @Test
    fun durationSplitsHoursAndMinutes() {
        assertEquals("8h 30m", formatDuration(510))
        assertEquals("1h 1m", formatDuration(61))
    }

    @Test
    fun durationWithExactHoursOmitsMinutes() {
        assertEquals("1h", formatDuration(60))
    }

    @Test
    fun durationUnderAnHourOmitsHours() {
        assertEquals("30m", formatDuration(30))
    }

    @Test
    fun nonPositiveDurationIsBlank() {
        assertEquals("", formatDuration(0))
        assertEquals("", formatDuration(-10))
    }

    @Test
    fun stopsLabels() {
        assertEquals("Nonstop", stopsLabel(0))
        assertEquals("1 stop", stopsLabel(1))
        assertEquals("2 stops", stopsLabel(2))
    }

    @Test
    fun countdownFormatsMinutesAndSeconds() {
        assertEquals("2:05", formatCountdown(125))
        assertEquals("1:00", formatCountdown(60))
        assertEquals("0:59", formatCountdown(59))
    }

    @Test
    fun countdownClampsAtZero() {
        assertEquals("0:00", formatCountdown(0))
        assertEquals("0:00", formatCountdown(-20))
    }

    @Test
    fun conditionsBothAllowed() {
        val conditions = ConditionsDto(PenaltyRuleDto(true), PenaltyRuleDto(true))
        assertEquals(listOf("Refundable", "Changeable"), conditionsLabels(conditions))
    }

    @Test
    fun conditionsDisallowedRefund() {
        val conditions = ConditionsDto(PenaltyRuleDto(false), null)
        assertEquals(listOf("Non-refundable"), conditionsLabels(conditions))
    }

    @Test
    fun conditionsChangeOnly() {
        val conditions = ConditionsDto(null, PenaltyRuleDto(true))
        assertEquals(listOf("Changeable"), conditionsLabels(conditions))
    }

    @Test
    fun conditionsEmptyWhenAbsent() {
        assertEquals(emptyList(), conditionsLabels(ConditionsDto(null, null)))
    }
}
