package com.vayunmathur.globalesim.domain

/**
 * Pure display math for the single-eSIM model. Integer cents only —
 * no float money anywhere. The server is authoritative; this is display-only.
 */
object QuoteMath {
    /** Payment bounds on both rails, mirroring the server (which enforces them). */
    const val PAYMENT_MIN_CENTS = 500L
    const val PAYMENT_MAX_CENTS = 2000L

    private val AMOUNT = Regex("""^\$?\s*(\d{1,6})(?:[.,](\d{1,2}))?$""")

    fun formatCents(cents: Long): String {
        val dollars = cents / 100
        val rem = (cents % 100).toString().padStart(2, '0')
        return "$$dollars.$rem"
    }

    /**
     * Parse a typed dollar amount to cents with integer math: "12", "12.5",
     * "$12.50" and the decimal-comma "12,50" all work. Null when unparseable.
     */
    fun parseAmountCents(text: String): Long? {
        val match = AMOUNT.matchEntire(text.trim()) ?: return null
        val dollars = match.groupValues[1].toLong()
        val cents = match.groupValues[2].padEnd(2, '0').toLong()
        return dollars * 100 + cents
    }
}
