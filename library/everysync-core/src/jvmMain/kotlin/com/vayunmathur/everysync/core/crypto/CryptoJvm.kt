package com.vayunmathur.everysync.core.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** JCE AES-256-GCM actual for desktop JVM (same layout as Android). */
actual object AesGcm {
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12
    private const val AES_KEY_BYTES = 32

    actual fun newContentKey(): ByteArray = ByteArray(AES_KEY_BYTES).also { SecureRandom().nextBytes(it) }

    actual fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return iv + cipher.doFinal(plaintext)
    }

    actual fun decrypt(key: ByteArray, data: ByteArray): ByteArray {
        val iv = data.copyOfRange(0, IV_LEN)
        val ct = data.copyOfRange(IV_LEN, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }
}

/** `java.security.SecureRandom` actual for desktop JVM. */
actual object PlatformRandom {
    actual fun nextBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }
}
