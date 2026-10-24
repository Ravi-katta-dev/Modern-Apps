package com.vayunmathur.globalesim.domain

/**
 * Builds the full SGP.22 activation code (`LPA:1$<smdp>$<matchingId>`) the
 * LPA needs, from the server's [rawCode] + [smdpAddress] pair.
 *
 * The server sometimes returns the code already joined but with the `$`
 * between SM-DP+ host and matching ID missing
 * (`LPA:1$<smdp><matchingId>`), which the LPA rejects straight to manual
 * entry. When the tail starts with our known SM-DP+ address, split there
 * and re-insert the separator instead of passing the fused string through.
 */
fun buildActivationCode(rawCode: String, smdpAddress: String): String {
    val body = rawCode.trim().let {
        if (it.startsWith("LPA:", ignoreCase = true)) it.substring(4) else it
    }
    if ('$' !in body) return "LPA:1\$$smdpAddress\$$body"
    val parts = body.split('$')
    if (parts.size >= 3) return "LPA:$body"
    // Fused `<smdp><matchingId>` tail: split at the known SM-DP+ address.
    val tail = parts.last()
    if (smdpAddress.isNotEmpty() &&
        tail.startsWith(smdpAddress) &&
        tail.length > smdpAddress.length
    ) {
        return "LPA:1\$$smdpAddress\$${tail.substring(smdpAddress.length)}"
    }
    return "LPA:$body"
}
