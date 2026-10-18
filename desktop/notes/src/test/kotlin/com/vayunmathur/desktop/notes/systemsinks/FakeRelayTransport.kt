package com.vayunmathur.desktop.notes.systemsinks

import com.vayunmathur.everysync.core.crypto.AesGcm
import com.vayunmathur.everysync.core.network.RelayTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.io.encoding.Base64

/**
 * In-memory [RelayTransport] for system-sink tests: no network, no PQC.
 * Channel logs hold ENCRYPTED envelopes (`Base64(AES_GCM(key, utf8))`, same
 * shape as the real `appendRecords`/`pullRecords` helpers), so tests exercise
 * the real envelope path through the shared helpers.
 *
 * Failure injection: set [failPullChannels] to make [pullRaw] return null for
 * a channel (transport failure → the driver must abort with zero writes, per
 * the null-pull invariant).
 */
class FakeRelayTransport : RelayTransport {
    val logs = LinkedHashMap<String, MutableList<String>>()
    val appended = LinkedHashMap<String, MutableList<String>>()
    var failPullChannels: Set<String> = emptySet()

    override val deviceId: String = "test-device"
    override val publicBundle: ByteArray = "fake-bundle".encodeToByteArray()

    override suspend fun register(): Boolean = true
    override suspend fun getKey(id: String): ByteArray? = null

    override suspend fun appendRaw(channel: String, items: List<String>): Int? {
        val log = logs.getOrPut(channel) { mutableListOf() }
        log += items
        appended.getOrPut(channel) { mutableListOf() } += items
        return log.size
    }

    override suspend fun pullRaw(channel: String, since: Int): List<String>? {
        if (channel in failPullChannels) return null
        val log = logs[channel] ?: return emptyList()
        if (since < 0 || since > log.size) return null
        return log.drop(since)
    }

    override fun subscribe(channel: String): Flow<String> = emptyFlow()

    override suspend fun sign(data: ByteArray): ByteArray = data
    override suspend fun verify(publicBundle: ByteArray, data: ByteArray, signature: ByteArray): Boolean = true
    override suspend fun seal(bundle: ByteArray, data: ByteArray): ByteArray = data
    override suspend fun unseal(data: ByteArray): ByteArray = data
    override fun newVaultKey(): ByteArray = ByteArray(32)
    override fun newVaultId(): String = "vault-test"

    /**
     * Seeds [channel] with plaintext envelopes encrypted under [key] (what
     * `appendRecords` would have written).
     */
    fun seed(channel: String, key: ByteArray, plaintexts: List<String>) {
        val log = logs.getOrPut(channel) { mutableListOf() }
        for (plain in plaintexts) {
            log += Base64.encode(AesGcm.encrypt(key, plain.encodeToByteArray()))
        }
    }

    /** Decrypts every envelope appended to [channel] during the test. */
    fun appendedPlaintexts(channel: String, key: ByteArray): List<String> =
        (appended[channel] ?: emptyList()).mapNotNull { blob ->
            runCatching { AesGcm.decrypt(key, Base64.decode(blob)).decodeToString() }.getOrNull()
        }
}
