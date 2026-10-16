package com.vayunmathur.everysync.core.network

import kotlinx.serialization.Serializable

/**
 * An invite to a shared vault: carries everything a device needs to join it.
 * Sealed with the recipient's PQC bundle; the relay only sees ciphertext.
 */
@Serializable
data class Invite(
    val vaultId: String,
    val key: String,
    val title: String,
    val dataType: String = "",
    val role: String = RelayRoles.EDITOR,
    val ownerKey: String = "",
    val ownerDeviceId: String = "",
    val epoch: Int = 0,
)

/** A request to join a vault, sealed to the owner. Public ids only — no key. */
@Serializable
data class JoinRequest(
    val kind: String = "req",
    val vaultId: String,
    val dataType: String = "",
    val requesterId: String,
    val name: String = "",
)

/** An author-signed envelope batch: author id, signature over [payload], and the payload JSON. */
@Serializable
data class SignedEnvelope(val author: String, val sig: String, val payload: String)

/** A vault member + their role, distributed as owner-signed records on the members channel. */
@Serializable
data class Member(val id: String, val name: String = "", val role: String = RelayRoles.EDITOR)

/** An owner-signed key epoch for cryptographic read-revocation. */
@Serializable
data class KeyEpoch(val epoch: Int, val wraps: Map<String, String>, val sig: String)

/**
 * An ownership handoff, signed by the *current* owner. Members follow the signed
 * chain from the original owner key to determine the current owner.
 */
@Serializable
data class OwnerTransferRecord(val newOwnerId: String, val newOwnerKey: String, val sig: String)

/** An owner-signed vault title (latest valid one wins). */
@Serializable
data class SignedTitle(val title: String, val sig: String)

class InvitesResult(val invites: List<Invite>, val seq: Int)
class InboxResult(val invites: List<Invite>, val requests: List<JoinRequest>, val seq: Int)

/**
 * One decrypted record-log pull. [rawCount] is the envelope count on the
 * relay; [undecryptable] is how many failed to decrypt under the vault key.
 * When most records fail to decrypt the key/epoch is suspect — the caller
 * must treat that as a failure (abort), not as an empty vault.
 */
class RecordsResult(
    val items: List<String>,
    val seq: Int,
    val rawCount: Int = items.size,
    val undecryptable: Int = 0,
) {
    /** True when decryption failed for every record that exists: key suspect, abort. */
    val fullyUndecryptable: Boolean get() = rawCount > 0 && items.isEmpty()
}
