package com.vayunmathur.globalesim.domain

/**
 * Pure display math for the single-eSIM model. Integer cents only —
 * no float money anywhere. The server is authoritative; this is display-only.
 */
object QuoteMath {
    /** Rough USD headline: the cheapest rate rounds to ~$1/GB. */
    fun formatCents(cents: Long): String {
        val dollars = cents / 100
        val rem = (cents % 100).toString().padStart(2, '0')
        return "$$dollars.$rem"
    }

    /** Data MB a wallet balance buys at a per-GB price. Floor — never overpromise. */
    fun mbFor(pricePerGbCents: Long, walletCents: Long): Long {
        if (pricePerGbCents <= 0) return 0
        return walletCents * 1024 / pricePerGbCents
    }
}
