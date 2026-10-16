package com.vayunmathur.everysync.core.crypto

/**
 * AES-256-GCM content encryption. The random 12-byte IV is prepended to the
 * ciphertext (same layout as the Android `E2ee` object).
 *
 * Platform actuals: JCE `AES/GCM/NoPadding` on Android/JVM, CryptoKit
 * `AES.GCM` on Apple. This fills the known Rust gap (AES is NOT in the
 * `e2ee_pqc` crate) — do NOT add AES to Rust.
 */
expect object AesGcm {
    /** A fresh random 256-bit content key. */
    fun newContentKey(): ByteArray

    /** Encrypts [plaintext] with [key]; the random 12-byte IV is prepended. */
    fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray

    /** Decrypts data produced by [encrypt] (IV prepended). */
    fun decrypt(key: ByteArray, data: ByteArray): ByteArray
}

/**
 * Cryptographically secure random bytes.
 * Platform actuals: `SecureRandom` on Android/JVM, `SecRandomCopyBytes` on Apple.
 */
expect object PlatformRandom {
    fun nextBytes(size: Int): ByteArray
}
