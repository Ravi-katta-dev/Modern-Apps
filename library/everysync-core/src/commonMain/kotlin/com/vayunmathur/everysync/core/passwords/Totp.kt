package com.vayunmathur.everysync.core.passwords

/**
 * HMAC-SHA1 over a key and message. Platform actuals: JCE `Mac("HmacSHA1")`
 * on Android/JVM, CryptoKit `HMAC<Insecure.SHA1>` on Apple. Pure framing
 * (counter bytes, dynamic truncation) stays in common ([Totp]).
 */
expect object HmacSha1 {
    fun mac(key: ByteArray, message: ByteArray): ByteArray
}

object Totp {
    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    // RFC 4648 base32 decode. Input is expected already trimmed/uppercased with
    // padding stripped; any character outside the alphabet is ignored.
    private fun base32Decode(input: String): ByteArray {
        val out = ArrayList<Byte>(input.length * 5 / 8 + 1)
        var buffer = 0
        var bitsLeft = 0
        for (c in input) {
            val value = BASE32_ALPHABET.indexOf(c)
            if (value < 0) continue
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.add(((buffer shr bitsLeft) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }

    /** Big-endian 8-byte counter encoding (replaces `java.nio.ByteBuffer`). */
    private fun counterBytes(counter: Long): ByteArray {
        val out = ByteArray(8)
        for (i in 0 until 8) out[i] = (counter ushr (56 - 8 * i)).toByte()
        return out
    }

    private fun hotp(key: ByteArray, counter: Long): String {
        val hash = HmacSha1.mac(key, counterBytes(counter))
        val offset = hash.last().toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        return (binary % 1_000_000).toString().padStart(6, '0')
    }

    fun generate(secret: String, epochSecond: Long): String {
        val cleaned = secret.trim().replace("=", "").replace(" ", "").uppercase()
        val key = base32Decode(cleaned)
        val timeStep = 30L
        val counter = epochSecond / timeStep
        return hotp(key, counter)
    }
}
