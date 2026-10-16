package com.vayunmathur.everysync.core.network

import com.vayunmathur.everysync.core.platform.SecureStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * iOS `RelayTransport`: Ktor-Darwin engine + sec-trust pinning land with the
 * Phase 3 Apple shell (which also wires the XCFramework + cinterop Rust
 * bindings). Until then this stub keeps the iosMain source set honest about
 * the interface without pretending to ship networking.
 */
class IosRelayTransport(
    private val store: SecureStore,
    private val crypto: PqcCrypto,
) : RelayTransport {
    override val deviceId: String get() = throw UnsupportedOperationException("iOS transport lands in Phase 3")
    override val publicBundle: ByteArray get() = throw UnsupportedOperationException("iOS transport lands in Phase 3")
    override suspend fun register(): Boolean = throw UnsupportedOperationException("iOS transport lands in Phase 3")
    override suspend fun getKey(id: String): ByteArray? = throw UnsupportedOperationException("iOS transport lands in Phase 3")
    override suspend fun appendRaw(channel: String, items: List<String>): Int? =
        throw UnsupportedOperationException("iOS transport lands in Phase 3")
    override suspend fun pullRaw(channel: String, since: Int): List<String>? =
        throw UnsupportedOperationException("iOS transport lands in Phase 3")
    override fun subscribe(channel: String): Flow<String> = emptyFlow()
    override suspend fun sign(data: ByteArray): ByteArray =
        throw UnsupportedOperationException("iOS transport lands in Phase 3")
    override suspend fun verify(publicBundle: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        throw UnsupportedOperationException("iOS transport lands in Phase 3")
    override suspend fun seal(bundle: ByteArray, data: ByteArray): ByteArray =
        throw UnsupportedOperationException("iOS transport lands in Phase 3")
    override suspend fun unseal(data: ByteArray): ByteArray =
        throw UnsupportedOperationException("iOS transport lands in Phase 3")
    override fun newVaultKey(): ByteArray = throw UnsupportedOperationException("iOS transport lands in Phase 3")
    override fun newVaultId(): String = throw UnsupportedOperationException("iOS transport lands in Phase 3")
}
