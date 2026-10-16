package com.vayunmathur.everysync.core.crypto

/**
 * Apple actuals. AES-GCM uses CryptoKit `AES.GCM` via a small Swift bridge.
 *
 * Kotlin/Native cannot call CryptoKit generics directly; the
 * `EverysyncCoreCrypto` Swift helper in the Apple shell target (Phase 3)
 * exposes `aesGcmEncrypt/aesGcmDecrypt` as C functions this actual calls via
 * cinterop. Layout contract: `[12B nonce] + [ciphertext] + [16B tag]`,
 * matching the JCE actuals.
 */
actual object AesGcm {
    actual fun newContentKey(): ByteArray = PlatformRandom.nextBytes(32)

    actual fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        // Phase 3: route through the EverysyncCoreCrypto Swift bridge.
        throw UnsupportedOperationException("Apple AES-GCM lands with the Phase 3 shell")
    }

    actual fun decrypt(key: ByteArray, data: ByteArray): ByteArray {
        // Phase 3: route through the EverysyncCoreCrypto Swift bridge.
        throw UnsupportedOperationException("Apple AES-GCM lands with the Phase 3 shell")
    }
}

actual object PlatformRandom {
    actual fun nextBytes(size: Int): ByteArray {
        // Phase 3: SecRandomCopyBytes via cinterop once the iosMain source set
        // is wired to the Apple SDK in CI.
        throw UnsupportedOperationException("Apple RNG lands with the Phase 3 shell")
    }
}
