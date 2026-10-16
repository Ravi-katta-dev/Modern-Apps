package com.vayunmathur.everysync.core.network

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Transport surface for the E2EE relay: one function per endpoint/operation.
 * Platform actuals: Ktor-OkHttp on Android/JVM, Ktor-Darwin on Apple.
 *
 * Loss-prevention invariant: a failed pull returns null — NEVER an empty
 * list. The merge driver aborts the cycle on null; folding a failure as
 * empty would read every baseline-present id as deleted.
 */
interface RelayTransport {
    /** This device's stable relay id. */
    val deviceId: String

    /** This device's PQC public bundle (for sealing invites to us). */
    val publicBundle: ByteArray

    /** Idempotent: registers this device's public bundle. */
    suspend fun register(): Boolean

    /** Fetches a peer's public bundle by device id. */
    suspend fun getKey(id: String): ByteArray?

    /**
     * Appends raw string records to [channel]; returns the new sequence, or
     * null on transport failure.
     */
    suspend fun appendRaw(channel: String, items: List<String>): Int?

    /**
     * Pulls raw string records from [channel] since [since]; null on
     * transport failure (NOT an empty channel — abort, don't merge).
     */
    suspend fun pullRaw(channel: String, since: Int): List<String>?

    /** Best-effort live subscription to [channel] (relay WebSocket fan-out). */
    fun subscribe(channel: String): Flow<String>

    // --- Identity / crypto primitives (PQC in Rust, AES in shared Kotlin) ---

    suspend fun sign(data: ByteArray): ByteArray
    suspend fun verify(publicBundle: ByteArray, data: ByteArray, signature: ByteArray): Boolean
    suspend fun seal(bundle: ByteArray, data: ByteArray): ByteArray
    suspend fun unseal(data: ByteArray): ByteArray

    /** A fresh random 256-bit vault content key. */
    fun newVaultKey(): ByteArray

    /** A fresh random vault id. */
    fun newVaultId(): String
}
