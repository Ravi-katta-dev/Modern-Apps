package com.vayunmathur.securemail.domain.mail

/**
 * Pure MIME helpers shared by the receive path (service-side decrypt on
 * arrival) and the UI-side decrypt fallback. No Android imports — base64
 * stays at the call sites.
 */
object MimeCodec {

    /**
     * Extracts the raw PGP bytes from an RFC 3156 multipart/encrypted envelope
     * (second part, base64 `application/octet-stream`). Returns the base64
     * payload string, or null when the input is not such an envelope (legacy
     * bare blobs pass through undecrypted at a higher layer).
     */
    fun extractPgpPayloadB64(envelope: ByteArray): String? {
        return try {
            val text = envelope.toString(Charsets.UTF_8)
            if (!text.contains("application/pgp-encrypted")) return null
            // Split on boundary markers; the data part follows the octet-stream header.
            val octetIdx = text.indexOf("application/octet-stream")
            if (octetIdx < 0) return null
            val bodyStart = text.indexOf("\r\n\r\n", octetIdx).takeIf { it >= 0 }
                ?: text.indexOf("\n\n", octetIdx).takeIf { it >= 0 }
                ?: return null
            val afterHeaders = if (text.startsWith("\r\n\r\n", bodyStart)) {
                text.substring(bodyStart + 4)
            } else {
                text.substring(bodyStart + 2)
            }
            // Cut at the closing boundary; de-whitespace the base64.
            val end = afterHeaders.indexOf("--maemail-").takeIf { it >= 0 } ?: afterHeaders.length
            val b64 = afterHeaders.substring(0, end).filterNot { it.isWhitespace() }
            if (b64.isEmpty()) null else b64
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Minimal parser for the MIME skeleton the composer emits (headers +
     * trailing `--boundary--`). Returns (subject, body, from). The payload is
     * already authenticated PGP plaintext at this point; this only splits it.
     */
    fun parseMime(mime: String): Triple<String, String, String> {
        val lines = mime.lines()
        var from = ""
        var subject = ""
        var i = 0
        while (i < lines.size && lines[i].isNotBlank()) {
            val line = lines[i]
            when {
                line.startsWith("From:", ignoreCase = true) ->
                    from = line.substringAfter(':').trim()
                line.startsWith("Subject:", ignoreCase = true) ->
                    subject = line.substringAfter(':').trim()
            }
            i++
        }
        // Skip the blank line plus the multipart preamble (boundary markers and
        // the Version part); the body is the last text block before --boundary--.
        val body = StringBuilder()
        var inBody = false
        for (line in lines.drop(i)) {
            if (line.startsWith("--maemail-")) {
                inBody = false
                continue
            }
            if (line.startsWith("Content-Type: text/plain", ignoreCase = true)) {
                inBody = false // headers of the part follow; blank line starts text
                continue
            }
            if (inBody) body.appendLine(line)
            if (line.isBlank()) inBody = true
        }
        return Triple(subject, body.toString().trimEnd(), from)
    }
}
