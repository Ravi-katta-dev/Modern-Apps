package com.vayunmathur.globalesim.domain

/**
 * NOWPayments sometimes returns a cleartext `http:` checkout link, which the
 * app's network security config blocks. Their checkout serves TLS, so
 * upgrade to `https:` — anything already secure passes through untouched.
 */
fun upgradeCheckoutUrl(url: String): String =
    if (url.startsWith("http://", ignoreCase = true)) {
        "https://" + url.substring("http://".length)
    } else {
        url
    }
