package com.vayunmathur.everysync.core.network

import com.vayunmathur.everysync.core.crypto.AesGcm
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

private val envelopesJson = Json { ignoreUnknownKeys = true }

/**
 * Envelope encoding shared by every target, ported from the Android
 * `RelaySync` transport (which stays as the Android reference).
 *
 * Records are `Base64(AES_GCM(key, utf8))` per item; roster/epochs/transfers
 * double-wrap as `Base64(Json(...))`; invites and join requests are PQC-sealed
 * to the recipient's inbox. All authority stays client-side — the relay only
 * ever sees ciphertext.
 *
 * Key-hygiene invariant: vault keys are never user-visible. Share links carry
 * only vault/owner ids; keys move exclusively via PQC-sealed inbox invites.
 */

/** Appends AES-encrypted envelopes to a vault's record log; returns the new sequence, or null. */
suspend fun RelayTransport.appendRecords(channel: String, key: ByteArray, items: List<String>): Int? {
    val blobs = items.map { Base64.encode(AesGcm.encrypt(key, it.encodeToByteArray())) }
    return appendRaw(channel, blobs)
}

/**
 * Pulls + decrypts a vault's record log from [since]; null on transport failure.
 *
 * A null return is NOT an empty log: the caller must abort the cycle. Folding
 * a failure as empty would read every baseline-present id as deleted (see
 * `RelayMerge`). [RecordsResult.rawCount] is how many envelopes the relay
 * holds; [RecordsResult.items.size] is how many decrypted under [key] — a
 * large gap means a key/epoch problem, not an empty vault.
 */
suspend fun RelayTransport.pullRecords(channel: String, key: ByteArray, since: Int): RecordsResult? {
    val blobs = pullRaw(channel, since) ?: return null
    var undecryptable = 0
    val items = blobs.mapNotNull { b ->
        runCatching { AesGcm.decrypt(key, Base64.decode(b)).decodeToString() }.getOrNull()
            ?: run { undecryptable++; null }
    }
    return RecordsResult(items, seq = since + blobs.size, rawCount = blobs.size, undecryptable = undecryptable)
}

/**
 * Seals a vault invite to [recipientId]'s inbox. Carries the vault id, the AES vault
 * key, the data type, role, and owner identity — everything a device needs to join.
 */
suspend fun RelayTransport.sendInvite(
    recipientId: String,
    vaultId: String,
    dataType: String,
    key: ByteArray,
    title: String,
    role: String,
    ownerKeyB64: String,
    ownerDeviceId: String,
    epoch: Int = 0,
): Boolean {
    val peerBundle = getKey(recipientId) ?: return false
    val invite = envelopesJson.encodeToString(
        Invite(vaultId, Base64.encode(key), title, dataType, role, ownerKeyB64, ownerDeviceId, epoch),
    )
    val blob = Base64.encode(seal(peerBundle, invite.encodeToByteArray()))
    return appendRaw(inboxChannel(recipientId), listOf(blob)) != null
}

/** Pulls + unseals invites addressed to this device from [since]. */
suspend fun RelayTransport.pullInvites(since: Int): InvitesResult {
    val inbox = pullInbox(since)
    return InvitesResult(inbox.invites, inbox.seq)
}

/**
 * Sends a PQC-sealed join request to a vault owner's inbox. Carries only public ids
 * (never the AES vault key); the owner approves with one tap, which triggers the
 * existing seal-to-recipient [sendInvite]. Rides the same `inbox:<ownerId>` channel
 * as invites — distinguished by the `kind:"req"` discriminator.
 */
suspend fun RelayTransport.sendJoinRequest(
    ownerId: String,
    vaultId: String,
    dataType: String,
    requesterId: String,
    name: String,
): Boolean {
    val ownerBundle = getKey(ownerId) ?: return false
    val req = envelopesJson.encodeToString(
        JoinRequest(vaultId = vaultId, dataType = dataType, requesterId = requesterId, name = name),
    )
    val blob = Base64.encode(seal(ownerBundle, req.encodeToByteArray()))
    return appendRaw(inboxChannel(ownerId), listOf(blob)) != null
}

/**
 * Tolerant inbox reader: decrypts each blob and sorts it into invites (default kind,
 * for backward compatibility) vs join requests (`kind:"req"`). Unparseable/foreign
 * blobs are skipped so one bad item never drops the batch.
 */
suspend fun RelayTransport.pullInbox(since: Int): InboxResult {
    val blobs = pullRaw(inboxChannel(deviceId), since) ?: return InboxResult(emptyList(), emptyList(), since)
    val invites = mutableListOf<Invite>()
    val requests = mutableListOf<JoinRequest>()
    for (b in blobs) {
        val plain = runCatching { unseal(Base64.decode(b)) }.getOrNull() ?: continue
        val text = plain.decodeToString()
        val kind = runCatching {
            envelopesJson.decodeFromString<InboxEnvelope>(text).kind
        }.getOrNull().orEmpty()
        if (kind == "req") {
            runCatching { envelopesJson.decodeFromString<JoinRequest>(text) }.getOrNull()?.let { requests.add(it) }
        } else {
            runCatching { envelopesJson.decodeFromString<Invite>(text) }.getOrNull()?.let { invites.add(it) }
        }
    }
    return InboxResult(invites, requests, since + blobs.size)
}

@kotlinx.serialization.Serializable
private data class InboxEnvelope(val kind: String = "")
