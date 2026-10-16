package com.vayunmathur.everysync.core.network

import com.vayunmathur.everysync.core.crypto.AesGcm
import com.vayunmathur.everysync.core.crypto.PlatformRandom
import com.vayunmathur.everysync.core.platform.SecureStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

private val ktorJson = Json { ignoreUnknownKeys = true }

/**
 * `RelayTransport` over OkHttp: POST JSON to `/register|/getkey|/append|/pull`
 * plus a WebSocket fan-in for [subscribe]. Platform actuals supply the
 * pinned `RelayHttpClient` ([OkHttpRelayClient.pinned] on Android/JVM,
 * Darwin/sec-trust on iOS in Phase 3); envelope codec, channel names, and the
 * null-on-failure invariant live here in common.
 *
 * Loss-prevention invariant: [pullRaw] returns null on ANY transport failure
 * (exception, non-200, unparseable body) — never an empty list.
 */
abstract class SharedRelayTransport(
    private val store: SecureStore,
    private val crypto: PqcCrypto,
) : RelayTransport {

    /** Pinned HTTP client, supplied per target (OkHttp on Android/JVM, Darwin on Apple). */
    protected abstract fun client(): RelayHttpClient

    /** This transport's stable device id, loaded or created in [init]. */
    private var selfDeviceId: String = ""
    override val deviceId: String get() = selfDeviceId

    private lateinit var identity: PqcIdentityHandle
    override val publicBundle: ByteArray get() = identity.publicBundle

    /**
     * Idempotent: loads/creates this device's PQC identity + stable id and
     * registers its public bundle. Must be called before any other op.
     */
    suspend fun init(): Boolean {
        identity = PqcIdentityHandle.loadOrCreate(store, crypto, "personal")
        var id = store.getString("personalDeviceId")
        if (id == null) {
            id = newUuidString()
            store.setString("personalDeviceId", id, onlyIfAbsent = true)
            id = store.getString("personalDeviceId") ?: id
        }
        selfDeviceId = id
        return register()
    }

    override suspend fun register(): Boolean {
        val body = ktorJson.encodeToString(RegisterReq(deviceId, Base64.encode(publicBundle)))
        return post("/register", body)
    }

    override suspend fun getKey(id: String): ByteArray? {
        val body = ktorJson.encodeToString(IdReq(id))
        val raw = raw("/getkey", body) ?: return null
        if (raw.status != 200) return null
        return runCatching { Base64.decode(raw.body) }.getOrNull()
    }

    override suspend fun appendRaw(channel: String, items: List<String>): Int? {
        val body = ktorJson.encodeToString(AppendReq(channel, items))
        val raw = raw("/append", body) ?: return null
        if (raw.status != 200) return null
        return runCatching { ktorJson.decodeFromString<SeqResp>(raw.body).seq }.getOrNull()
    }

    override suspend fun pullRaw(channel: String, since: Int): List<String>? {
        val body = ktorJson.encodeToString(PullReq(channel, since))
        val raw = raw("/pull", body) ?: return null
        if (raw.status != 200) return null
        return runCatching { ktorJson.decodeFromString<PullResp>(raw.body).actions }.getOrNull()
    }

    override fun subscribe(channel: String): Flow<String> = flow {
        client().subscribe(RELAY_WS_URL, channel).collect { emit(it) }
    }

    override suspend fun sign(data: ByteArray): ByteArray = identity.sign(data)
    override suspend fun verify(publicBundle: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        crypto.verify(publicBundle, data, signature)
    override suspend fun seal(bundle: ByteArray, data: ByteArray): ByteArray = crypto.seal(bundle, data)
    override suspend fun unseal(data: ByteArray): ByteArray = identity.decrypt(data)
    override fun newVaultKey(): ByteArray = AesGcm.newContentKey()
    override fun newVaultId(): String = newUuidString()

    private suspend fun post(path: String, body: String): Boolean {
        val raw = raw(path, body) ?: return false
        return raw.status in 200..299
    }

    private suspend fun raw(path: String, body: String): RelayHttpResponse? =
        runCatching { client().post(RELAY_BASE_URL + path, body) }.getOrNull()

    @Serializable private data class RegisterReq(val id: String, val key: String)
    @Serializable private data class IdReq(val id: String)
    @Serializable private data class AppendReq(val channel: String, val actions: List<String>)
    @Serializable private data class PullReq(val channel: String, val since: Int)
    @Serializable private data class SeqResp(val seq: Int = 0)
    @Serializable private data class PullResp(val actions: List<String> = emptyList(), val seq: Int = 0)
}

/** Minimal HTTP surface the OkHttp/Darwin actuals implement (keeps engines out of common). */
interface RelayHttpClient {
    suspend fun post(url: String, jsonBody: String): RelayHttpResponse
    fun subscribe(wsUrl: String, channel: String): Flow<String>
}

data class RelayHttpResponse(val status: Int, val body: String)

/**
 * PQC primitives. Production actuals delegate to the shared Rust crate
 * (`lib.rs` via JNI/JNA/cinterop); tests use [FakePqcCrypto].
 */
interface PqcCrypto {
    fun generateKem(): Pair<ByteArray, ByteArray>
    fun generateDsa(): Pair<ByteArray, ByteArray>
    fun bundle(kemPub: ByteArray, dsaPub: ByteArray): ByteArray
    fun splitBundle(bundle: ByteArray): Pair<ByteArray, ByteArray>?
    fun seal(bundle: ByteArray, data: ByteArray): ByteArray
    fun decrypt(kemPrivate: ByteArray, data: ByteArray): ByteArray
    fun sign(dsaPrivate: ByteArray, data: ByteArray): ByteArray
    fun verify(publicBundle: ByteArray, data: ByteArray, signature: ByteArray): Boolean
}

/** Device PQC identity: ML-KEM pair (decrypt) + ML-DSA pair (sign). */
class PqcIdentityHandle(
    val publicBundle: ByteArray,
    private val kemPrivate: ByteArray,
    private val dsaPrivate: ByteArray,
    private val crypto: PqcCrypto,
) {
    fun decrypt(ciphertext: ByteArray): ByteArray = crypto.decrypt(kemPrivate, ciphertext)
    fun sign(data: ByteArray): ByteArray = crypto.sign(dsaPrivate, data)

    companion object {
        suspend fun loadOrCreate(store: SecureStore, crypto: PqcCrypto, prefix: String): PqcIdentityHandle {
            readPersisted(store, crypto, prefix)?.let { return it }
            val (kemPub, kemPriv) = crypto.generateKem()
            val (dsaPub, dsaPriv) = crypto.generateDsa()
            store.setBytes("${prefix}KemPub", kemPub, onlyIfAbsent = true)
            store.setBytes("${prefix}KemPriv", kemPriv, onlyIfAbsent = true)
            store.setBytes("${prefix}DsaPub", dsaPub, onlyIfAbsent = true)
            store.setBytes("${prefix}DsaPriv", dsaPriv, onlyIfAbsent = true)
            // Re-read final persisted (winner of concurrent race).
            return readPersisted(store, crypto, prefix)
                ?: error("PQC identity store returned no keys")
        }

        private suspend fun readPersisted(
            store: SecureStore,
            crypto: PqcCrypto,
            prefix: String,
        ): PqcIdentityHandle? {
            val kemPub = store.getBytes("${prefix}KemPub") ?: return null
            val kemPriv = store.getBytes("${prefix}KemPriv") ?: return null
            val dsaPub = store.getBytes("${prefix}DsaPub") ?: return null
            val dsaPriv = store.getBytes("${prefix}DsaPriv") ?: return null
            return PqcIdentityHandle(crypto.bundle(kemPub, dsaPub), kemPriv, dsaPriv, crypto)
        }
    }
}

/** Test-only PQC: XOR "seal" + prefixed "signatures". NEVER production crypto. */
class FakePqcCrypto : PqcCrypto {
    override fun generateKem(): Pair<ByteArray, ByteArray> {
        val pub = ("kem-pub-" + randomSuffix()).encodeToByteArray()
        val priv = ("kem-priv-" + randomSuffix()).encodeToByteArray()
        return pub to priv
    }

    override fun generateDsa(): Pair<ByteArray, ByteArray> {
        val pub = ("dsa-pub-" + randomSuffix()).encodeToByteArray()
        val priv = ("dsa-priv-" + randomSuffix()).encodeToByteArray()
        return pub to priv
    }

    override fun bundle(kemPub: ByteArray, dsaPub: ByteArray): ByteArray =
        ("bundle|${Base64.encode(kemPub)}|${Base64.encode(dsaPub)}").encodeToByteArray()

    override fun splitBundle(bundle: ByteArray): Pair<ByteArray, ByteArray>? {
        val parts = bundle.decodeToString().split("|")
        if (parts.size != 3 || parts[0] != "bundle") return null
        return Base64.decode(parts[1]) to Base64.decode(parts[2])
    }

    override fun seal(bundle: ByteArray, data: ByteArray): ByteArray =
        (Base64.encode(bundle) + "." + Base64.encode(data)).encodeToByteArray()

    override fun decrypt(kemPrivate: ByteArray, data: ByteArray): ByteArray {
        // Fake seal embeds the bundle, not the private key — recover by lookup
        // is impossible, so tests seal/unseal through the same instance only
        // for shape (see RelayConformanceTest envelope assertions).
        val s = data.decodeToString()
        val payload = s.substringAfter(".", missingDelimiterValue = "")
        return if (payload.isEmpty()) data else Base64.decode(payload)
    }

    override fun sign(dsaPrivate: ByteArray, data: ByteArray): ByteArray =
        ("sig:${Base64.encode(dsaPrivate)}:${Base64.encode(data)}").encodeToByteArray()

    override fun verify(publicBundle: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        signature.decodeToString().endsWith(":${Base64.encode(data)}")

    private fun randomSuffix(): String =
        PlatformRandom.nextBytes(6).joinToString("") { it.toInt().and(0xff).toString(16).padStart(2, '0') }
}

/** Random UUID string without `java.util.UUID` (common-safe, matches `Uuid.random()` shape). */
fun newUuidString(): String {
    val b = PlatformRandom.nextBytes(16)
    b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
    b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
    val h = b.joinToString("") { it.toInt().and(0xff).toString(16).padStart(2, '0') }
    return "${h.take(8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
}
