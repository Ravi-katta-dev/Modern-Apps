package com.vayunmathur.everysync.core.passwords

/**
 * HMAC-SHA1 over a key and message (Apple actual via CryptoKit `HMAC<Insecure.SHA1>`).
 * Lands with the Phase 3 Apple shell.
 */
actual object HmacSha1 {
    actual fun mac(key: ByteArray, message: ByteArray): ByteArray {
        throw UnsupportedOperationException("Apple HMAC lands with the Phase 3 shell")
    }
}
