package com.vayunmathur.securemail.platform

import android.app.Application
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.biometric.unlockDatabaseWithBiometrics
import com.vayunmathur.library.log.Log
import com.vayunmathur.securemail.data.CachedKey
import com.vayunmathur.securemail.data.MailConfig
import com.vayunmathur.securemail.data.MailRepository
import com.vayunmathur.securemail.data.OutboxMessage
import com.vayunmathur.securemail.data.SenderKeys
import com.vayunmathur.securemail.data.StoredMessage
import com.vayunmathur.securemail.domain.mail.AddressValidator
import com.vayunmathur.securemail.domain.mail.KeyLookupPolicy
import com.vayunmathur.securemail.domain.mail.MessageEnvelope
import com.vayunmathur.securemail.domain.pgp.PgpCrypto
import com.vayunmathur.securemail.network.KeyLookupHttp
import com.vayunmathur.securemail.network.MailSocket
import com.vayunmathur.securemail.platform.crypto.KeyManager
import com.vayunmathur.securemail.service.MailSocketService
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Aggregate UI state for SecureMail. */
data class MailUiState(
    val onboarded: Boolean = false,
    val locked: Boolean = true,
    val address: String = "",
    val fingerprintHex: String = "",
    val socketConnected: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val busy: Boolean = false,
    /** Exported public key awaiting user copy/share (Settings). */
    val exportArmor: String? = null,
)

private const val TAG = "MailViewModel"

/**
 * Owns identity, decryption, send gating, and the socket lifecycle.
 * Decrypt + private-key reads happen on Dispatchers.IO and only while unlocked.
 */
class MailViewModel(app: Application) : AndroidViewModel(app) {

    private val config = MailConfig(app)
    private val keyManager = KeyManager(app)
    private val repo: MailRepository = MailRepository.get(app)

    var state by mutableStateOf(
        MailUiState(
            onboarded = keyManager.hasIdentity(),
            locked = keyManager.hasIdentity(),
        ),
    )
        private set

    val messages = repo.messageDao().observeAll()
    val outboxCount = repo.outboxDao().observeCount()
    val outboxMessages = repo.outboxDao().observeAll()

    /**
     * In-memory S2K passphrase for the PGP secret ring. Sealed in
     * Keystore-wrapped storage at claim time ([KeyManager.storeKeyPassphrase])
     * and loaded here on first need — the user enters it once at setup (and
     * keeps it for account recovery) and is never asked again.
     */
    private var passphraseCache: String? = null

    /**
     * Returns the S2K passphrase, loading it from Keystore-wrapped storage
     * on first use. Must be called on Dispatchers.IO (Keystore I/O).
     */
    private fun requirePassphrase(): String =
        passphraseCache ?: keyManager.getKeyPassphrase()?.also { passphraseCache = it }
        ?: error("No key passphrase stored — restore from your recovery passphrase.")

    init {
        config.settings
            .onEach { s ->
                state = state.copy(
                    address = s.address,
                    fingerprintHex = s.fingerprintHex,
                    onboarded = s.isConfigured,
                    locked = if (!s.isConfigured) false else state.locked,
                )
            }
            .launchIn(viewModelScope)
        // Live relay status: the socket flips this directly, so the UI never
        // polls a stale flag (which is why it used to say "offline" forever).
        MailSocket.connectedFlow
            .onEach { up -> state = state.copy(socketConnected = up) }
            .launchIn(viewModelScope)
    }

    // ------------------------------------------------------------------
    // Identity: claim + unlock
    // ------------------------------------------------------------------

    /** Generates the PGP identity, claims the address over the socket, persists. */
    fun claimAddress(localPart: String, passphrase: String) {
        val address = AddressValidator.canonicalAddress(localPart)
        if (address == null) {
            state = state.copy(error = "Use 3–64 lowercase letters, digits, dots or dashes.")
            return
        }
        if (passphrase.length < 8) {
            state = state.copy(error = "Use a passphrase of at least 8 characters.")
            return
        }
        state = state.copy(busy = true, error = null, message = "Generating your key…")
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val identity = PgpCrypto.generateIdentity(
                        "$address <$address>",
                        passphrase,
                    )
                    keyManager.storeSecretArmor(identity.armoredSecret)
                    // Seal the S2K passphrase in Keystore-wrapped storage now:
                    // S2K runs transparently after this and is never asked for
                    // again (the passphrase is still the recovery secret).
                    keyManager.storeKeyPassphrase(passphrase)
                    keyManager.storeOwnAddress(address)
                    config.setIdentity(address, identity.armoredPublic, identity.fingerprintHex)
                    passphraseCache = passphrase
                    // Registration rides the first SUB; the service connects now.
                    MailSocketService.start(getApplication())
                    address
                }
            }
            state = outcome.fold(
                onSuccess = {
                    state.copy(
                        busy = false, locked = false, onboarded = true,
                        message = "Address $it claimed. Share your public key from Settings.",
                    )
                },
                onFailure = {
                    Log.status(TAG, "claim failed", it)
                    state.copy(busy = false, error = it.message ?: "Key generation failed.")
                },
            )
        }
    }

    /**
     * First unlock attempt from MainActivity: if no identity exists there is
     * nothing to gate; otherwise prompt biometrics before composition starts.
     */
    fun unlockIfReady(
        activity: FragmentActivity,
        onUnlocked: () -> Unit,
        onLocked: () -> Unit,
    ) {
        if (!keyManager.hasIdentity()) {
            state = state.copy(locked = false, onboarded = false)
            onUnlocked()
            return
        }
        unlockDatabaseWithBiometrics(
            activity,
            onSuccess = {
                state = state.copy(locked = false)
                onUnlocked()
            },
            onFailure = { message ->
                state = state.copy(
                    locked = true,
                    error = message ?: "Unlock to read your mail.",
                )
                onLocked()
            },
        )
    }

    /** Manual unlock from the Lock screen. */
    fun unlock(activity: FragmentActivity) {
        unlockDatabaseWithBiometrics(
            activity,
            onSuccess = { state = state.copy(locked = false, error = null) },
            onFailure = { message ->
                state = state.copy(error = message ?: "Unlock to read your mail.")
            },
        )
    }

    fun exportPublicKey() {
        viewModelScope.launch {
            // The public half was persisted in the clear at claim time (MailConfig) —
            // it is public by design, so no key unwrapping needed.
            val armor = withContext(Dispatchers.IO) {
                runCatching {
                    config.settings.first().publicArmor.ifBlank { null }
                }.getOrNull()
            }
            state = state.copy(exportArmor = armor, error = armor?.let { null } ?: "Unlock first.")
        }
    }

    fun forgetIdentity() {
        viewModelScope.launch {
            MailSocket.stop()
            keyManager.clear()
            config.clear()
            state = MailUiState()
        }
    }

    // ------------------------------------------------------------------
    // Inbound: store + decrypt
    // ------------------------------------------------------------------

    /** Called by the socket layer for every MSG frame (already in Dispatchers.IO). */
    suspend fun onInboundMessage(id: String, unencrypted: Boolean, raw: ByteArray) {
        // Sender/from is inside the encrypted payload; store now, label on decrypt.
        repo.messageDao().upsert(
            StoredMessage(
                id = id,
                from = "",
                toJson = "[\"${state.address}\"]",
                dateMillis = System.currentTimeMillis(),
                messageId = id,
                blobBase64 = Base64.encodeToString(raw, Base64.NO_WRAP),
                unencrypted = unencrypted,
            ),
        )
        decryptStored(id)
    }

    /** Decrypts one stored message in memory; never writes plaintext to the server. */
    fun decryptStored(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val stored = repo.messageDao().get(id) ?: return@runCatching
                    if (stored.decryptedBody != null || stored.unencrypted) return@runCatching
                    // S2K passphrase loads transparently from Keystore-wrapped
                    // storage (sealed at claim time) — never asked for again.
                    val passphrase = requirePassphrase()
                    val raw = Base64.decode(stored.blobBase64, Base64.NO_WRAP)
                    val pgpBytes = extractPgpPayload(raw) ?: raw
                    val provisional = PgpCrypto.decrypt(
                        pgpBytes, keyManager.getSecretArmor(), passphrase,
                    ).toString(Charsets.UTF_8)
                    val (_, _, from) = parseMime(provisional)
                    // Same two-step as arrival: resolve sender key post-decrypt,
                    // re-verify for the badge.
                    val (mime, verified) = if (from.isNotBlank()) {
                        val senderKey = SenderKeys.resolve(getApplication(), from)
                        if (senderKey != null) {
                            val checked = PgpCrypto.decryptVerify(
                                pgpBytes,
                                keyManager.getSecretArmor(),
                                passphrase,
                                senderKey,
                            )
                            checked.payload.toString(Charsets.UTF_8) to
                                checked.signerFingerprintHex.ifBlank { null }
                        } else {
                            provisional to null
                        }
                    } else {
                        provisional to null
                    }
                    val (subject, body, resolvedFrom) = parseMime(mime)
                    repo.messageDao().storeDecrypted(
                        id, body, subject,
                        verified = verified,
                    )
                    if (resolvedFrom.isNotBlank()) {
                        repo.messageDao().upsert(stored.copy(from = resolvedFrom))
                    }
                }.onFailure {
                    state = state.copy(error = "Could not decrypt this message.")
                    Log.status(TAG, "decryptStored failed", it)
                }
            }
        }
    }

    /**
     * Extracts the raw PGP bytes from an RFC 3156 multipart/encrypted envelope.
     * Delegates to [com.vayunmathur.securemail.domain.mail.MimeCodec].
     */
    private fun extractPgpPayload(envelope: ByteArray): ByteArray? {
        val b64 = com.vayunmathur.securemail.domain.mail.MimeCodec
            .extractPgpPayloadB64(envelope) ?: return null
        return try {
            android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        } catch (_: Exception) {
            null
        }
    }

    /** Plaintext fallback path: stores the body with the unencrypted flag set. */
    suspend fun onInboundPlaintext(id: String, from: String, subject: String, body: String) {
        repo.messageDao().upsert(
            StoredMessage(
                id = id,
                from = from,
                toJson = "[\"${state.address}\"]",
                dateMillis = System.currentTimeMillis(),
                messageId = id,
                blobBase64 = "",
                decryptedBody = body,
                decryptedSubject = subject,
                unencrypted = true,
            ),
        )
    }

    // ------------------------------------------------------------------
    // Outbound: trust gate + publish
    // ------------------------------------------------------------------

    /**
     * Resolves trust for every recipient: vault cache → live socket directory
     * (own domain) → WKD advanced/direct (external). No other discovery.
     */
    suspend fun resolveTrust(addresses: List<String>): List<KeyLookupPolicy.RecipientTrust> =
        withContext(Dispatchers.IO) {
            addresses.map { address ->
                val clean = address.trim().lowercase()
                val cached = repo.keyDao().get(clean)
                if (cached != null) {
                    return@map KeyLookupPolicy.RecipientTrust(
                        address = clean,
                        hasKey = true,
                        source = KeyLookupPolicy.KeySource.Cached,
                        fingerprintHex = cached.fingerprintHex,
                    )
                }
                // Own-domain directory over the live socket (the server also
                // serves this same key over WKD, so external clients agree).
                if (AddressValidator.isOwnDomain(clean)) {
                    val socketHit = runCatching { MailSocket.getKey(clean) }.getOrNull()
                    if (socketHit?.armoredPublic != null) {
                        cacheKey(clean, socketHit.armoredPublic, KeyLookupPolicy.KeySource.OwnDirectory)
                        return@map KeyLookupPolicy.RecipientTrust(
                            address = clean,
                            hasKey = true,
                            source = KeyLookupPolicy.KeySource.OwnDirectory,
                            fingerprintHex = runCatching {
                                PgpCrypto.fingerprintHex(socketHit.armoredPublic)
                            }.getOrNull(),
                        )
                    }
                }
                // External: WKD advanced then direct, over HTTPS with system trust.
                for (url in KeyLookupPolicy.wkdUrls(clean)) {
                    val wkd = KeyLookupHttp.fetchWkdKey(url)
                    if (wkd != null) {
                        cacheKey(clean, wkd, KeyLookupPolicy.KeySource.Wkd)
                        return@map KeyLookupPolicy.RecipientTrust(
                            address = clean,
                            hasKey = true,
                            source = KeyLookupPolicy.KeySource.Wkd,
                            fingerprintHex = runCatching { PgpCrypto.fingerprintHex(wkd) }.getOrNull(),
                        )
                    }
                }
                KeyLookupPolicy.RecipientTrust(clean, hasKey = false, source = KeyLookupPolicy.KeySource.None)
            }
        }

    private suspend fun cacheKey(address: String, armor: String, source: KeyLookupPolicy.KeySource) {
        val fingerprint = runCatching { PgpCrypto.fingerprintHex(armor) }.getOrNull() ?: return
        repo.keyDao().upsert(
            CachedKey(
                address = address,
                armoredPublic = armor,
                fingerprintHex = fingerprint,
                source = source.toString(),
                updatedMillis = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Sends a message: encrypts per-recipient on device to every WKD key found,
     * publishes over the live socket when up, else queues to the outbox worker.
     * Recipients without a key receive the message unencrypted (badged `plain`
     * on receipt) — there is no opt-in; no WKD key simply means no encryption.
     * Changed/lost keys still abort the send for explicit acknowledgement.
     */
    fun send(
        to: List<String>,
        subject: String,
        body: String,
        inReplyTo: String? = null,
        onDone: (sent: Boolean) -> Unit = {},
    ) {
        state = state.copy(busy = true, error = null)
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val trust = resolveTrust(to)
                    when (val decision = KeyLookupPolicy.decide(trust)) {
                        is KeyLookupPolicy.SendDecision.NeedsKeyChangeAck ->
                            error("Key changed for ${decision.changed.first().address}. Review in Settings first.")
                        is KeyLookupPolicy.SendDecision.UnencryptedFallback ->
                            Unit // no key: send unencrypted, badged plain on receipt
                        KeyLookupPolicy.SendDecision.Encrypted -> Unit
                    }
                    val encryptedTo = trust.filter { it.hasKey }
                    val plainTo = trust.filter { !it.hasKey }.map { it.address }
                    val mime = buildMimeBody(subject, body)
                    // Sign with the own identity: recipients verify against our
                    // public key (directory/WKD) and show verified badges.
                    val ownSecret = keyManager.getSecretArmor()
                    val ownPassphrase = requirePassphrase()
                    var queued = 0
                    // Per-recipient encryption: each key-known recipient gets a blob.
                    for (t in encryptedTo) {
                        val cached = repo.keyDao().get(t.address) ?: continue
                        val raw = PgpCrypto.encryptSigned(
                            mime, cached.armoredPublic, ownSecret, ownPassphrase,
                        )
                        val blob = buildEncryptedBody(raw)
                        val ok = MailSocket.publish(t.address, blob)
                        if (!ok) {
                            queueOutbox(listOf(t.address), subject, body, inReplyTo)
                            queued++
                        }
                    }
                    // Keyless recipients go out as plaintext frames for the
                    // server to federate (own-domain) or relay onward —
                    // badged `plain` wherever they land.
                    for (address in plainTo) {
                        val ok = MailSocket.publish(address, mime, unencrypted = true)
                        if (!ok) {
                            queueOutbox(listOf(address), subject, body, inReplyTo)
                            queued++
                        }
                    }
                    if (queued > 0) "Queued $queued message(s) — retry from Outbox when online." else "Sent."
                }
            }
            state = outcome.fold(
                onSuccess = { msg ->
                    state.copy(busy = false, message = msg)
                        .also { onDone(true) }
                },
                onFailure = {
                    Log.status(TAG, "send failed", it)
                    state.copy(busy = false, error = it.message ?: "Send failed.")
                        .also { onDone(false) }
                },
            )
        }
    }

    private suspend fun queueOutbox(
        to: List<String>,
        subject: String,
        body: String,
        inReplyTo: String?,
    ) {
        repo.outboxDao().insert(
            OutboxMessage(
                toJson = to.joinToString(","),
                subject = subject,
                body = body,
                inReplyTo = inReplyTo,
                createdMillis = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Drains the local outbox straight to the server over the live socket —
     * no trust re-resolution (that already happened at send time).
     *
     * Key-known recipients re-encrypt to the cached key; keyless recipients
     * publish as plaintext frames for the server to federate. Entries stay
     * queued while offline; anything still failing after publish records an
     * attempt for the next retry.
     */
    fun retryOutbox() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (!MailSocket.connected) {
                    state = state.copy(error = "Relay offline — kept in outbox.")
                    return@withContext
                }
                val pending = repo.outboxDao().all()
                var sent = 0
                var failed = 0
                val ownSecret = keyManager.getSecretArmor()
                val ownPassphrase = requirePassphrase()
                for (entry in pending) {
                    val ok = runCatching {
                        val to = entry.toJson.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        val mime = buildMimeBody(entry.subject, entry.body)
                        var entryOk = true
                        for (address in to) {
                            val cached = repo.keyDao().get(address)
                            if (cached != null) {
                                val raw = PgpCrypto.encryptSigned(
                                    mime, cached.armoredPublic, ownSecret, ownPassphrase,
                                )
                                if (MailSocket.publish(address, buildEncryptedBody(raw))) continue
                            } else {
                                if (MailSocket.publish(address, mime, unencrypted = true)) continue
                            }
                            entryOk = false
                        }
                        entryOk
                    }.getOrDefault(false)
                    if (ok) {
                        repo.outboxDao().delete(entry.rowId)
                        sent++
                    } else {
                        repo.outboxDao().noteAttempt(entry.rowId, "relay publish failed")
                        failed++
                    }
                }
                state = when {
                    failed > 0 -> state.copy(error = "$failed message(s) still queued.")
                    sent > 0 -> state.copy(message = "Sent $sent message(s).")
                    else -> state.copy(message = "Outbox is empty.")
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Sync + misc
    // ------------------------------------------------------------------

    fun refresh() {
        viewModelScope.launch {
            state = state.copy(socketConnected = MailSocket.connected)
        }
    }

    fun markRead(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.messageDao().markRead(id) }
        }
    }

    fun messageFlow(id: String): StoredMessage? = null

    fun envelopeOf(stored: StoredMessage): MessageEnvelope = MessageEnvelope(
        id = stored.id,
        from = stored.from,
        to = runCatching {
            stored.toJson.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }.getOrDefault(listOf(state.address)),
        dateMillis = stored.dateMillis,
        messageId = stored.messageId,
        unencrypted = stored.unencrypted,
    )

    fun dismissMessages() {
        state = state.copy(message = null, error = null, exportArmor = null)
    }

    /**
     * Minimal parser for the MIME skeleton [buildMimeBody] emits (headers +
     * trailing `--boundary--`). Returns (subject, body, from). The payload is
     * already authenticated PGP plaintext at this point; this only splits it.
     */
    private fun parseMime(mime: String): Triple<String, String, String> =
        com.vayunmathur.securemail.domain.mail.MimeCodec.parseMime(mime)

    private fun newMessageId(): String = "${UUID.randomUUID()}@maemail.org"

    /**
     * Minimal PGP/MIME body (RFC 3156 multipart/encrypted skeleton). The actual
     * encryption envelopes this whole body; the server relays it opaquely.
     */
    private fun buildMimeBody(subject: String, body: String): ByteArray {
        val safeSubject = subject.replace(Regex("[\\r\\n]"), " ")
        return (
            "From: ${state.address}\r\n" +
                "Subject: $safeSubject\r\n" +
                "Message-ID: <${newMessageId()}>\r\n" +
                "MIME-Version: 1.0\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Transfer-Encoding: 8bit\r\n" +
                "\r\n" +
                "$body\r\n"
            ).toByteArray(Charsets.UTF_8)
    }

    /**
     * Wraps raw PGP bytes in the RFC 3156 multipart/encrypted envelope for
     * federated transport: control part (`application/pgp-encrypted`,
     * `Version: 1`) + data part (`application/octet-stream`).
     */
    private fun buildEncryptedBody(encrypted: ByteArray): ByteArray {
        val boundary = "maemail-${UUID.randomUUID().toString().take(8)}"
        val armor = android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
        // Fold armor at 64 cols per RFC 3156 line-length limits.
        val folded = armor.chunked(64).joinToString("\r\n")
        return (
            "MIME-Version: 1.0\r\n" +
                "Content-Type: multipart/encrypted; boundary=\"$boundary\"; protocol=\"application/pgp-encrypted\"\r\n" +
                "\r\n" +
                "--$boundary\r\n" +
                "Content-Type: application/pgp-encrypted\r\n" +
                "\r\n" +
                "Version: 1\r\n" +
                "\r\n" +
                "--$boundary\r\n" +
                "Content-Type: application/octet-stream; name=\"encrypted.asc\"\r\n" +
                "Content-Description: OpenPGP encrypted message\r\n" +
                "Content-Disposition: inline; filename=\"encrypted.asc\"\r\n" +
                "\r\n" +
                "$folded\r\n" +
                "--$boundary--\r\n"
            ).toByteArray(Charsets.UTF_8)
    }
}
