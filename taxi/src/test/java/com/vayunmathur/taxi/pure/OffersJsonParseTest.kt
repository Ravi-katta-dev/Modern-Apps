package com.vayunmathur.taxi.pure

import com.vayunmathur.taxi.data.Provider
import com.vayunmathur.taxi.network.lyft.LyftOffersParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Pins offers-response JSON parsing through the production parser:
 * quote mapping, ETA/duration conversion, surge, seats, tokens, expiry
 * and session ids, plus empty/malformed envelopes.
 *
 * Calls LyftOffersParser.parseJson/toQuoteJson directly with a lenient
 * Json config; no network or Android is involved.
 */
class OffersJsonParseTest {
    private val parser = LyftOffersParser(Json { ignoreUnknownKeys = true; isLenient = true })

    private fun offerJson(
        name: String = "Lyft Standard",
        min: Long = 500L,
        max: Long = 900L,
        extra: String = "",
    ): String = """{"offer_product_id":"lyft","id":"o1","offer_token":"tok",
      "cost_estimate":{"ride_type":"Lyft","currency":"USD",
      "estimated_cost_cents_min":$min,"estimated_cost_cents_max":$max,
      "estimated_duration_seconds":1200,"primetime_multiplier":1.5,
      "cost_token":"ct","cost_token_expiry_time":123$extra},
      "ride_type_details":{"display_properties":{"name":"$name"},"seats":4},
      "ride_travel_details":{"pickup_estimate":{"duration_range":{"duration_ms":240000}}}}"""

    private fun response(vararg offers: String): String =
        """{"offers":{"purchase_session_id":"ps1","offers_response_id":"or1",
          "offers_list":[${offers.joinToString(",")}]}}"""

    @Test
    fun fullOfferMapsEveryField() {
        val parsed = parser.parseJson(response(offerJson()))
        assertEquals(1, parsed.quotes.size)
        val q = parsed.quotes[0]
        assertEquals(Provider.LYFT, q.provider)
        assertEquals("Lyft Standard", q.displayName)
        assertEquals(500L, q.fareLowMinor)
        assertEquals(900L, q.fareHighMinor)
        assertEquals("USD", q.currency)
        assertEquals(4, q.pickupEtaMinutes)
        assertEquals(20, q.tripDurationMinutes)
        assertEquals(1.5, q.surgeMultiplier)
        assertEquals(4, q.capacity)
        assertEquals("o1", q.offerId)
        assertEquals("tok", q.offerToken)
        assertEquals("ct", q.costToken)
        assertEquals("Lyft", q.rideType)
        assertEquals(123_000L, q.costTokenExpiryMs)
        assertEquals("ps1", parsed.purchaseSessionId)
        assertEquals("or1", parsed.offersResponseId)
    }

    @Test
    fun stringEncodedInt64FieldsParse() {
        val json = offerJson().replace("240000", "\"240000\"").replace("123", "\"123\"")
        val parsed = parser.parseJson(response(json))
        assertEquals(4, parsed.quotes[0].pickupEtaMinutes)
        assertEquals(123_000L, parsed.quotes[0].costTokenExpiryMs)
    }

    @Test
    fun couponDiscountLowersFareAndKeepsOriginals() {
        val json = offerJson(extra = ""","applicable_coupons":[{"discount_amount_min":200,
          "discount_amount_max":300}]""")
        val q = parser.parseJson(response(json)).quotes[0]
        assertEquals(300L, q.fareLowMinor)
        assertEquals(600L, q.fareHighMinor)
        assertEquals(500L, q.originalFareLowMinor)
        assertEquals(900L, q.originalFareHighMinor)
        assertTrue(q.hasDiscount)
    }

    @Test
    fun emptyEnvelopeGivesEmptyResult() {
        val parsed = parser.parseJson("{}")
        assertEquals(emptyList(), parsed.quotes)
        assertNull(parsed.purchaseSessionId)
        assertNull(parsed.offersResponseId)
    }

    @Test
    fun missingOffersListGivesEmptyQuotesButKeepsSession() {
        val parsed = parser.parseJson("""{"offers":{"purchase_session_id":"ps9"}}""")
        assertEquals(emptyList(), parsed.quotes)
        assertEquals("ps9", parsed.purchaseSessionId)
    }

    @Test
    fun offerWithoutPriceIsDropped() {
        val noPrice = """{"offer_product_id":"lyft"}"""
        val parsed = parser.parseJson(response(noPrice, offerJson()))
        assertEquals(1, parsed.quotes.size)
    }

    @Test
    fun offerWithoutNameIsDropped() {
        val element = Json.parseToJsonElement(
            """{"cost_estimate":{"estimated_cost_cents_min":1}}""",
        ) as JsonObject
        assertNull(parser.toQuoteJson(element))
    }

    @Test
    fun upfrontOnlyOfferBecomesExactFare() {
        val json = """{"offer_product_id":"lyft",
          "cost_estimate":{"ride_type":"Lux","currency":"USD","upfront_cost_cents":2500}}"""
        val element = Json.parseToJsonElement(json) as JsonObject
        val q = parser.toQuoteJson(element)!!
        assertEquals(2500L, q.fareLowMinor)
        assertEquals(2500L, q.fareHighMinor)
    }

    @Test
    fun missingEtaAndDurationStayNull() {
        val json = """{"offer_product_id":"lyft",
          "cost_estimate":{"ride_type":"Bike","currency":"USD","upfront_cost_cents":300}}"""
        val element = Json.parseToJsonElement(json) as JsonObject
        val q = parser.toQuoteJson(element)!!
        assertNull(q.pickupEtaMinutes)
        assertNull(q.tripDurationMinutes)
        assertNull(q.surgeMultiplier)
    }

    @Test
    fun missingCurrencyDefaultsToUsd() {
        val json = """{"offer_product_id":"lyft",
          "cost_estimate":{"ride_type":"Bike","upfront_cost_cents":300}}"""
        val element = Json.parseToJsonElement(json) as JsonObject
        assertEquals("USD", parser.toQuoteJson(element)!!.currency)
    }
}
