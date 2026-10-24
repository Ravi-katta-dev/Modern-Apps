package com.vayunmathur.globalesim.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class CheckoutUrlsTest {
    @Test
    fun httpCheckoutUpgradesToHttps() {
        assertEquals(
            "https://nowpayments.io/payment/?iid=123",
            upgradeCheckoutUrl("http://nowpayments.io/payment/?iid=123"),
        )
    }

    @Test
    fun httpsCheckoutPassesThrough() {
        val url = "https://nowpayments.io/payment/?iid=123"
        assertEquals(url, upgradeCheckoutUrl(url))
    }
}
