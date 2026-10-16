package com.vayunmathur.everysync.core.passwords

/**
 * HMAC-SHA1 over a key and message (Android/JVM actual via JCE).
 */
actual object HmacSha1 {
    actual fun mac(key: ByteArray, message: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA1")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "RAW"))
        return mac.doFinal(message)
    }
}
