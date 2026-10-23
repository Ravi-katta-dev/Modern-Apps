package com.vayunmathur.securemail.network

import com.vayunmathur.library.log.Log
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.WebSocketClient
import com.vayunmathur.library.network.WsSession
import com.vayunmathur.library.network.webSocket
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Live-socket mail relay, mirroring FindFamily's binary wire design:
 *
 * ```
 * client→server SUB     : [0x01][u16 addrLen][address UTF-8][armored public key…]
 * client→server PUB     : [0x02][flags][u16 rcptLen][recipient UTF-8][raw PGP/MIME…]
 * client→server GETKEY  : [0x04][u16 addrLen][address UTF-8]
 * client→server FETCH   : [0x06][u16 idLen][message id UTF-8]
 * server→client MSG     : [0x03][flags][u32 blobLen][message id UTF-8][raw PGP/MIME…]
 * server→client KEYRESP : [0x05][status][u16 addrLen][address UTF-8][armored public key…]
 * server→client FETCHED : [0x07][u16 idLen][message id UTF-8][u32 blobLen][raw PGP/MIME…]
 * ```
 *
 * flags bit0: 0 = encrypted PGP/MIME, 1 = unencrypted fallback (explicit opt-in only).
 * KEYRESP status: 0 = none, 1 = found (key follows).
 *
 * SUB doubles as registration (address claim + public-key publish), exactly like
 * FindFamily's SUB-carries-bundle: a socket-only client never needs separate
 * HTTP register/getkey endpoints. The server fans out to live subscribers and
 * durably queues for offline recipients; each SUB drains the recipient's backlog.
 *
 * Everything on the wire is framed binary — no JSON, no base64 — converted only
 * at the HTTP-federation boundary server-side and the DB boundary client-side.
 */
object MailSocket {

    const val WS_URL = "wss://mail.maemail.org/mail/ws"

    const val OP_SUB: Byte = 0x01
    const val OP_PUB: Byte = 0x02
    const val OP_MSG: Byte = 0x03
    const val OP_GETKEY_REQ: Byte = 0x04
    const val OP_GETKEY_RESP: Byte = 0x05
    const val OP_FETCH_REQ: Byte = 0x06
    const val OP_FETCH_RESP: Byte = 0x07

    const val FLAG_PLAIN: Byte = 0x01

    const val KEY_NONE = 0
    const val KEY_FOUND = 1

    const val GETKEY_TIMEOUT_MS = 8_000L
    const val FETCH_TIMEOUT_MS = 15_000L
    const val PING_INTERVAL_MS = 30_000L
    const val LIVENESS_TIMEOUT_MS = 75_000L

    private const val BACKOFF_INITIAL_MS = 1_000L
    private const val BACKOFF_MAX_MS = 30_000L

    private const val TAG = "MailSocket"

    val json: Json = Json { ignoreUnknownKeys = true }

    /** In-flight key lookups keyed by lowercase address, completed on KEYRESP. */
    internal val pendingKeys = ConcurrentHashMap<String, CompletableDeferred<KeyResult?>>()

    /** In-flight fetches keyed by message id, completed on FETCHED. */
    internal val pendingFetches = ConcurrentHashMap<String, CompletableDeferred<ByteArray?>>()

    @Volatile internal var session: WsSession? = null
        private set(value) {
            field = value
            _connected.value = value != null
        }
    private var loop: Job? = null

    private val _connected = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** Live socket state for the UI to collect (replaces polling `connected`). */
    val connectedFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _connected

    val connected: Boolean get() = session != null

    data class KeyResult(val address: String, val armoredPublic: String?)

    /**
     * Start (no-op if running) the persistent relay loop. [identity] supplies the
     * address + public key for every (re)SUB so registration self-heals.
     * [onMessage] receives each inbound MSG frame's (id, flags, raw blob);
     * [onBump] fires on any inbound frame to refresh UI/sync state.
     */
    @Synchronized
    fun start(
        scope: CoroutineScope,
        identity: suspend () -> IdentitySnapshot?,
        onMessage: suspend (id: String, unencrypted: Boolean, raw: ByteArray) -> Unit,
        onBump: () -> Unit = {},
    ) {
        if (loop?.isActive == true) return
        stop()
        loop = scope.launch(Dispatchers.IO) {
            val outer = this
            var backoff = BACKOFF_INITIAL_MS
            while (isActive) {
                val conn = CoroutineScope(Dispatchers.IO + Job())
                runCatching {
                    // mail.maemail.org is a pinned first-party host → platform trust is
                    // wrong here; the app inits NetworkClient with FIRST_PARTY.
                    webSocket(WS_URL) {
                        session = this
                        val id = identity()
                        if (id == null) {
                            Log.status(TAG, "no identity; waiting for onboarding")
                            delay(BACKOFF_MAX_MS)
                            return@webSocket
                        }
                        send(subFrame(id.address, id.armoredPublic))
                        backoff = BACKOFF_INITIAL_MS
                        supervise(conn, outer, onMessage, onBump)
                    }
                }.onFailure { Log.status(TAG, "relay loop error", it) }
                conn.cancel()
                session = null
                pendingKeys.values.forEach { it.complete(null) }
                pendingKeys.clear()
                pendingFetches.values.forEach { it.complete(null) }
                pendingFetches.clear()
                if (!isActive) break
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(BACKOFF_MAX_MS)
            }
        }
    }

    @Synchronized
    fun stop() {
        loop?.cancel(); loop = null; session = null
        pendingKeys.values.forEach { it.complete(null) }
        pendingKeys.clear()
        pendingFetches.values.forEach { it.complete(null) }
        pendingFetches.clear()
    }

    /** Publish an already-encrypted PGP/MIME blob to [recipient]. False when offline. */
    suspend fun publish(recipient: String, raw: ByteArray, unencrypted: Boolean = false): Boolean {
        val s = session ?: return false
        return runCatching {
            s.send(pubFrame(recipient, raw, unencrypted))
            true
        }.onFailure { Log.status(TAG, "publish failed", it) }.getOrDefault(false)
    }

    /** Directory lookup over the socket (request/response with timeout). */
    suspend fun getKey(address: String): KeyResult? {
        val s = session ?: return null
        val key = address.lowercase()
        val deferred = CompletableDeferred<KeyResult?>()
        pendingKeys[key] = deferred
        return try {
            s.send(getKeyFrame(address))
            withTimeoutOrNull(GETKEY_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            Log.status(TAG, "getKey failed for $address", e)
            null
        } finally {
            pendingKeys.remove(key)
        }
    }

    /** Fetch one backlog message's blob by id (for missed fan-outs / fresh devices). */
    suspend fun fetch(id: String): ByteArray? {
        val s = session ?: return null
        val deferred = CompletableDeferred<ByteArray?>()
        pendingFetches[id] = deferred
        return try {
            s.send(fetchFrame(id))
            withTimeoutOrNull(FETCH_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            Log.status(TAG, "fetch failed for $id", e)
            null
        } finally {
            pendingFetches.remove(id)
        }
    }

    // ------------------------------------------------------------------
    // Session internals
    // ------------------------------------------------------------------

    data class IdentitySnapshot(val address: String, val armoredPublic: String)

    private suspend fun WsSession.supervise(
        conn: CoroutineScope,
        outer: CoroutineScope,
        onMessage: suspend (id: String, unencrypted: Boolean, raw: ByteArray) -> Unit,
        onBump: () -> Unit,
    ) {
        var lastInbound = System.currentTimeMillis()
        conn.launch {
            runCatching {
                incoming.collect { frame ->
                    lastInbound = System.currentTimeMillis()
                    if (frame is WebSocketClient.WsFrame.Binary) {
                        dispatch(frame.bytes, onMessage)
                    }
                    runCatching { onBump() }
                }
            }
        }
        try {
            while (outer.isActive) {
                conn.launch { runCatching { ping() } }
                delay(PING_INTERVAL_MS)
                if (System.currentTimeMillis() - lastInbound > LIVENESS_TIMEOUT_MS) {
                    Log.status(TAG, "socket half-open, reconnecting")
                    break
                }
            }
        } finally {
            runCatching { abort() }
            conn.cancel()
        }
    }

    internal suspend fun dispatch(
        buf: ByteArray,
        onMessage: suspend (id: String, unencrypted: Boolean, raw: ByteArray) -> Unit,
    ) {
        when (buf.firstOrNull()) {
            OP_MSG -> {
                val parsed = parseMsg(buf) ?: return
                onMessage(parsed.first, parsed.second, parsed.third)
            }
            OP_GETKEY_RESP -> parseKeyResp(buf)?.let { (addr, key) ->
                pendingKeys.remove(addr.lowercase())?.complete(KeyResult(addr, key))
            }
            OP_FETCH_RESP -> parseFetched(buf)?.let { (id, raw) ->
                pendingFetches.remove(id)?.complete(raw)
            }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------
    // Framing
    // ------------------------------------------------------------------

    internal fun subFrame(address: String, armoredPublic: String): ByteArray {
        val addr = address.lowercase().toByteArray(Charsets.UTF_8)
        val key = armoredPublic.toByteArray(Charsets.UTF_8)
        val out = ByteArray(1 + 2 + addr.size + key.size)
        out[0] = OP_SUB
        putU16(out, 1, addr.size)
        addr.copyInto(out, 3)
        key.copyInto(out, 3 + addr.size)
        return out
    }

    internal fun pubFrame(recipient: String, raw: ByteArray, unencrypted: Boolean): ByteArray {
        val rcpt = recipient.lowercase().toByteArray(Charsets.UTF_8)
        val out = ByteArray(1 + 1 + 2 + rcpt.size + raw.size)
        out[0] = OP_PUB
        out[1] = if (unencrypted) FLAG_PLAIN else 0
        putU16(out, 2, rcpt.size)
        rcpt.copyInto(out, 4)
        raw.copyInto(out, 4 + rcpt.size)
        return out
    }

    internal fun getKeyFrame(address: String): ByteArray {
        val addr = address.lowercase().toByteArray(Charsets.UTF_8)
        val out = ByteArray(1 + 2 + addr.size)
        out[0] = OP_GETKEY_REQ
        putU16(out, 1, addr.size)
        addr.copyInto(out, 3)
        return out
    }

    internal fun fetchFrame(id: String): ByteArray {
        val raw = id.toByteArray(Charsets.UTF_8)
        val out = ByteArray(1 + 2 + raw.size)
        out[0] = OP_FETCH_REQ
        putU16(out, 1, raw.size)
        raw.copyInto(out, 3)
        return out
    }

    /** MSG: [0x03][flags][u32 blobLen][id UTF-8][raw…] → (id, unencrypted, raw). */
    internal fun parseMsg(buf: ByteArray): Triple<String, Boolean, ByteArray>? {
        if (buf.size < 1 + 1 + 4 + 2) return null
        val unencrypted = (buf[1].toInt() and 0xFF) != 0
        val idLen = readU32(buf, 2)
        if (idLen < 0 || buf.size < 6 + idLen) return null
        val id = buf.copyOfRange(6, 6 + idLen).toString(Charsets.UTF_8)
        val raw = buf.copyOfRange(6 + idLen, buf.size)
        return Triple(id, unencrypted, raw)
    }

    /** KEYRESP: [0x05][status][u16 addrLen][address][key…] → (address, key?). */
    internal fun parseKeyResp(buf: ByteArray): Pair<String, String?>? {
        if (buf.size < 1 + 1 + 2) return null
        val status = buf[1].toInt()
        val addrLen = readU16(buf, 2)
        if (buf.size < 4 + addrLen) return null
        val addr = buf.copyOfRange(4, 4 + addrLen).toString(Charsets.UTF_8)
        val key = if (status == KEY_FOUND && buf.size > 4 + addrLen) {
            buf.copyOfRange(4 + addrLen, buf.size).toString(Charsets.UTF_8)
        } else {
            null
        }
        return addr to key
    }

    /** FETCHED: [0x07][u16 idLen][id][raw…] → (id, raw). */
    internal fun parseFetched(buf: ByteArray): Pair<String, ByteArray>? {
        if (buf.size < 1 + 2) return null
        val idLen = readU16(buf, 1)
        if (buf.size < 3 + idLen) return null
        val id = buf.copyOfRange(3, 3 + idLen).toString(Charsets.UTF_8)
        return id to buf.copyOfRange(3 + idLen, buf.size)
    }

    internal fun putU16(dst: ByteArray, off: Int, v: Int) {
        dst[off] = (v shr 8).toByte()
        dst[off + 1] = v.toByte()
    }

    internal fun readU16(src: ByteArray, off: Int): Int =
        ((src[off].toInt() and 0xFF) shl 8) or (src[off + 1].toInt() and 0xFF)

    internal fun readU32(src: ByteArray, off: Int): Int =
        ((src[off].toInt() and 0xFF) shl 24) or
            ((src[off + 1].toInt() and 0xFF) shl 16) or
            ((src[off + 2].toInt() and 0xFF) shl 8) or
            (src[off + 3].toInt() and 0xFF)
}

/** Minimal REST surface: WKD runs over plain HTTPS, not the relay socket. */
object KeyLookupHttp {
    suspend fun fetchText(url: String, useSystemTrust: Boolean): String? {
        return try {
            val r = NetworkClient.performRequest(
                url = url,
                useSystemTrust = useSystemTrust,
            )
            if (r.isSuccess) r.body else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fetches a WKD key record (binary OpenPGP key, possibly armored) and
     * returns it as canonical armor text via the native normalizer. WKD
     * responses are the raw key bytes, not JSON.
     */
    suspend fun fetchWkdKey(url: String): String? {
        return try {
            val r = NetworkClient.execute(
                url = url,
                useSystemTrust = true,
            )
            if (!r.isSuccess || r.bytes.isEmpty()) return null
            runCatching { com.vayunmathur.e2ee.Pgp.normalizeKey(r.bytes) }.getOrNull()
        } catch (_: Exception) {
            null
        }
    }
}

/** Federation DTOs shared with the server's /mail/v1 HTTP surface (Phase 2 SMTP). */
@Serializable
data class FederatedSendRequest(
    val from: String,
    val toExternal: List<String>,
    /** Armored PGP/MIME for key-known externals; null entries send clear. */
    val armoredBodies: List<String?>,
    val subject: String,
)
