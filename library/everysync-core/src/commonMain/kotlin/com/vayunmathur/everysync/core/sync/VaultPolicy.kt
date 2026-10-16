package com.vayunmathur.everysync.core.sync

import com.vayunmathur.everysync.core.network.KeyEpoch
import com.vayunmathur.everysync.core.network.Member
import com.vayunmathur.everysync.core.network.RelayRoles
import com.vayunmathur.everysync.core.network.RelayTransport
import com.vayunmathur.everysync.core.network.SignedEnvelope
import com.vayunmathur.everysync.core.network.keysChannel
import com.vayunmathur.everysync.core.network.membersChannel
import com.vayunmathur.everysync.core.network.ownerChannel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/**
 * Owner-signed membership roster for a personal E2EE vault.
 *
 * The roster lives on the `members:<vaultId>` relay channel as a batch of
 * [SignedEnvelope] records: each envelope's payload is the JSON list
 * of [Member] and its signature is the owner's PQC signature over the
 * raw payload bytes. Only envelopes with a valid owner signature are honored —
 * the relay is an untrusted log, so authority is enforced entirely client-side.
 *
 * Last record wins per member id. Revocation is `role="revoked"`; removing read
 * access additionally requires a key rotation (see [VaultKeys.rotateKey]).
 *
 * KMP port: the Android singleton's `RelaySync` static calls are replaced with
 * an injected [RelayTransport] so this runs on every target.
 */
class VaultRoster(private val transport: RelayTransport) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Owner-signs [members] and appends them to the roster channel. The caller
     * must be the vault owner (signing uses this device's identity) — enforced
     * by the share flow, not here.
     */
    suspend fun recordMembers(vaultId: String, members: List<Member>): Boolean =
        runCatching {
            val payload = json.encodeToString(members)
            val sig = Base64.encode(transport.sign(payload.encodeToByteArray()))
            val envelope = json.encodeToString(SignedEnvelope(transport.deviceId, sig, payload))
            transport.appendRaw(membersChannel(vaultId), listOf(envelope)) != null
        }.getOrDefault(false)

    /**
     * Reads + verifies the roster: only envelopes with a valid [ownerKey]
     * signature are honored. Returns member id → member, last record wins.
     *
     * A failed pull returns an empty map (fail closed): the merge driver
     * already aborts the record cycle before consulting the roster, and
     * vault resolution treats "not on roster" as "skip this vault".
     */
    suspend fun fetchRoster(vaultId: String, ownerKey: ByteArray): Map<String, Member> {
        if (ownerKey.isEmpty()) return emptyMap()
        val raw = runCatching { transport.pullRaw(membersChannel(vaultId), 0) }.getOrNull()
            ?: return emptyMap()
        val byId = LinkedHashMap<String, Member>()
        for (r in raw) {
            val envelope = runCatching {
                json.decodeFromString<SignedEnvelope>(r)
            }.getOrNull() ?: continue
            val sig = runCatching { Base64.decode(envelope.sig) }.getOrNull() ?: continue
            if (!transport.verify(ownerKey, envelope.payload.encodeToByteArray(), sig)) continue
            val members = runCatching {
                json.decodeFromString<List<Member>>(envelope.payload)
            }.getOrNull() ?: continue
            for (member in members) {
                if (member.id.isNotBlank()) byId[member.id] = member
            }
        }
        return byId
    }

    /** This device's role in the vault, or null when it is not on the verified roster. */
    suspend fun myRole(
        vaultId: String,
        ownerKey: ByteArray,
        deviceId: String = transport.deviceId,
    ): String? = fetchRoster(vaultId, ownerKey)[deviceId]?.role

    /** Owner sets/changes one member's role, or revokes with `role="revoked"`. Owner only. */
    suspend fun setRole(vaultId: String, memberId: String, name: String = "", role: String): Boolean =
        recordMembers(vaultId, listOf(Member(memberId, name, role)))
}

/**
 * Ownership transfers for a personal E2EE vault.
 *
 * Transfers live on the `owner:<vaultId>` channel as base64(JSON) records
 * signed by the *current* owner. Members follow the signed chain from the
 * vault's base owner key ([fetchOwnerKey]) to determine the current owner; a
 * transfer whose signature does not verify against the running key is skipped,
 * and each record is consumed at most once so a forked chain cannot loop.
 */
class OwnerTransfer(
    private val transport: RelayTransport,
    private val roster: VaultRoster,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Canonical bytes the current owner signs for a handoff: binds the vault
     * id, the new owner's device id and their PQC public bundle.
     */
    fun ownerSigningBytes(vaultId: String, newOwnerId: String, newOwnerKeyB64: String): ByteArray =
        "$vaultId|owner|$newOwnerId|$newOwnerKeyB64".encodeToByteArray()

    /**
     * Follows the signed transfer chain from [baseOwnerKeyB64] (the owner's PQC
     * bundle as issued in the invite) to the current owner key. Returns null
     * when the base key is missing/corrupt.
     */
    suspend fun fetchOwnerKey(vaultId: String, baseOwnerKeyB64: String): ByteArray? {
        var ownerKey = baseOwnerKeyB64.takeIf { it.isNotBlank() }
            ?.let { runCatching { Base64.decode(it) }.getOrNull() } ?: return null
        // A failed pull keeps the base key: a transfer chain we cannot read is
        // not a chain that doesn't exist. Vault resolution re-fetches every
        // cycle, so a recovered relay is picked up on retry.
        val transfers = runCatching { transport.pullRaw(ownerChannel(vaultId), 0) }
            .getOrNull()?.mapNotNull { decodeTransfer(it) } ?: return ownerKey
        val used = HashSet<Int>()
        var advanced = true
        while (advanced) {
            advanced = false
            for ((idx, transfer) in transfers.withIndex()) {
                if (idx in used) continue
                val sig = runCatching { Base64.decode(transfer.sig) }.getOrNull() ?: continue
                if (!transport.verify(
                        ownerKey,
                        ownerSigningBytes(vaultId, transfer.newOwnerId, transfer.newOwnerKey),
                        sig,
                    )
                ) {
                    continue
                }
                val decoded = runCatching { Base64.decode(transfer.newOwnerKey) }.getOrNull()
                if (decoded != null && decoded.isNotEmpty()) {
                    ownerKey = decoded
                    used.add(idx)
                    advanced = true
                }
            }
        }
        return ownerKey
    }

    /**
     * Transfers vault ownership to [newOwnerId]. Signs with this device's
     * identity (the caller must be the current owner), records the roster
     * change (new owner promoted, self demoted to editor) and appends the
     * signed transfer. Returns true on success.
     */
    suspend fun transferOwnership(
        vaultId: String,
        newOwnerId: String,
        newOwnerName: String = "",
        selfName: String = "",
        selfDeviceId: String = transport.deviceId,
    ): Boolean = runCatching {
        val newBundle = transport.getKey(newOwnerId) ?: return@runCatching false
        val newKeyB64 = Base64.encode(newBundle)
        // Last owner-signed roster change: promote the new owner, demote self.
        // The new-owner record must land before the transfer so verifiers see a
        // consistent roster even mid-handoff.
        if (!roster.recordMembers(
                vaultId,
                listOf(
                    Member(newOwnerId, newOwnerName, RelayRoles.OWNER),
                    Member(selfDeviceId, selfName, RelayRoles.EDITOR),
                ),
            )
        ) {
            return@runCatching false
        }
        val sig = Base64.encode(transport.sign(ownerSigningBytes(vaultId, newOwnerId, newKeyB64)))
        val transfer = json.encodeToString(
            com.vayunmathur.everysync.core.network.OwnerTransferRecord(newOwnerId, newKeyB64, sig),
        )
        transport.appendRaw(ownerChannel(vaultId), listOf(Base64.encode(transfer.encodeToByteArray()))) != null
    }.getOrDefault(false)

    private fun decodeTransfer(blob: String): com.vayunmathur.everysync.core.network.OwnerTransferRecord? =
        runCatching {
            json.decodeFromString<com.vayunmathur.everysync.core.network.OwnerTransferRecord>(
                Base64.decode(blob).decodeToString(),
            )
        }.getOrNull()
}

/**
 * Owner-signed key epochs for a personal E2EE vault: cryptographic
 * read-revocation after a member is removed or downgraded.
 *
 * Epochs live on the `keys:<vaultId>` channel as base64(JSON([KeyEpoch]))
 * records. Each epoch wraps the fresh AES content key with every remaining
 * member's PQC bundle; only epochs with a valid owner signature over
 * [epochSigningBytes] are honored.
 *
 * Members resolve the current key with [fetchCurrentKey]: the highest verified
 * epoch they can unseal, falling back to the invite (epoch 0) key.
 *
 * KMP port: `RelaySync` statics replaced with injected [RelayTransport].
 */
class VaultKeys(
    private val transport: RelayTransport,
    private val roster: VaultRoster,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Canonical bytes the owner signs for an epoch: binds the vault id, the
     * epoch number and every wrap (sorted by member id for determinism) to the
     * vault.
     */
    fun epochSigningBytes(vaultId: String, epoch: Int, wraps: Map<String, String>): ByteArray =
        (listOf(vaultId, epoch.toString()) +
            wraps.entries.sortedBy { it.key }.map { "${it.key}=${it.value}" })
            .joinToString("|").encodeToByteArray()

    /**
     * Resolves the current content key: the highest owner-signed epoch this
     * device can unseal, falling back to ([inviteEpoch], [inviteKey]).
     * Returns (epoch, key).
     *
     * A failed pull keeps the invite key (the epoch loop simply finds nothing
     * newer); epoch-bearing history stays unreadable until the pull succeeds,
     * which is fail-closed — the record cycle aborts before merging.
     */
    suspend fun fetchCurrentKey(
        vaultId: String,
        inviteKey: ByteArray,
        ownerKey: ByteArray?,
        inviteEpoch: Int = 0,
        deviceId: String = transport.deviceId,
    ): Pair<Int, ByteArray> {
        if (ownerKey == null || ownerKey.isEmpty()) return inviteEpoch to inviteKey
        var bestEpoch = inviteEpoch
        var bestKey = inviteKey
        val blobs = runCatching { transport.pullRaw(keysChannel(vaultId), 0) }.getOrNull()
            ?: return inviteEpoch to inviteKey
        for (blob in blobs) {
            val epoch = decodeEpoch(blob) ?: continue
            val sig = runCatching { Base64.decode(epoch.sig) }.getOrNull() ?: continue
            if (!transport.verify(ownerKey, epochSigningBytes(vaultId, epoch.epoch, epoch.wraps), sig)) continue
            val wrap = epoch.wraps[deviceId] ?: continue
            val key = runCatching { transport.unseal(Base64.decode(wrap)) }.getOrNull() ?: continue
            if (epoch.epoch >= bestEpoch) {
                bestEpoch = epoch.epoch
                bestKey = key
            }
        }
        return bestEpoch to bestKey
    }

    /**
     * Owner mints a new content key sealed to [memberBundles] (member id →
     * PQC public bundle) and publishes the signed epoch. Always includes
     * [ownerDeviceId] so the owner keeps access. Returns the new key, or null
     * when the publish fails. The caller re-baselines the vault's record log
     * under the new key — rotation without a re-push would leave remaining
     * members unable to read history.
     */
    suspend fun rotateKey(
        vaultId: String,
        currentEpoch: Int,
        memberBundles: Map<String, ByteArray>,
        ownerDeviceId: String = transport.deviceId,
        ownerBundle: ByteArray = transport.publicBundle,
    ): ByteArray? {
        val newEpoch = currentEpoch + 1
        val newKey = transport.newVaultKey()
        val wraps = HashMap<String, String>()
        for ((id, bundle) in memberBundles) {
            if (bundle.isEmpty()) continue
            wraps[id] = Base64.encode(transport.seal(bundle, newKey))
        }
        // Always include ourselves so the owner keeps access even when the
        // roster fetch raced.
        wraps[ownerDeviceId] = Base64.encode(transport.seal(ownerBundle, newKey))
        val sig = Base64.encode(transport.sign(epochSigningBytes(vaultId, newEpoch, wraps)))
        val record = Base64.encode(json.encodeToString(KeyEpoch(newEpoch, wraps, sig)).encodeToByteArray())
        val ok = runCatching { transport.appendRaw(keysChannel(vaultId), listOf(record)) }.getOrNull() != null
        return if (ok) newKey else null
    }

    /**
     * Downgrades/removes [memberId] from the roster and rotates the content key
     * so the removed member can no longer decrypt new content. Owner only.
     * Returns the new key, or null on failure.
     */
    suspend fun revokeAndRotate(
        vaultId: String,
        memberId: String,
        memberName: String = "",
        role: String = RelayRoles.REVOKED,
        currentEpoch: Int,
        memberBundles: Map<String, ByteArray>,
        ownerKey: ByteArray,
        ownerDeviceId: String = transport.deviceId,
    ): ByteArray? {
        if (!roster.setRole(vaultId, memberId, memberName, role)) return null
        val remaining = roster.fetchRoster(vaultId, ownerKey)
            .filter { it.key != memberId && it.value.role != RelayRoles.REVOKED }
            .mapNotNull { (id, _) -> memberBundles[id]?.let { id to it } }
            .toMap()
        return rotateKey(vaultId, currentEpoch, remaining, ownerDeviceId)
    }

    private fun decodeEpoch(blob: String): KeyEpoch? = runCatching {
        json.decodeFromString<KeyEpoch>(Base64.decode(blob).decodeToString())
    }.getOrNull()
}
