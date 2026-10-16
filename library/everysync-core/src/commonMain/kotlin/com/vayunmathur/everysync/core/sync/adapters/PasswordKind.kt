package com.vayunmathur.everysync.core.sync.adapters

import com.vayunmathur.everysync.core.data.RemotePassword
import com.vayunmathur.everysync.core.sync.EntityKind
import com.vayunmathur.everysync.core.sync.relayContentHash
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Relay merge behaviour for passwords and passkeys. Both ride the same
 * `passwords:<vaultId>` channel as native [RemotePassword] JSON (no KDBX
 * mapping); [RemotePassword.isPasskey] distinguishes the two halves.
 */
object PasswordKind : EntityKind<RemotePassword>() {
    override val kind = "password"

    /**
     * Passwords hard-delete locally (rows vanish; only the app-side snapshot
     * remembers them), so a healthy-read absence IS a genuine local delete.
     * See `EntityKind.localDeletesAreAuthoritative`.
     */
    override val localDeletesAreAuthoritative = false

    private val json = Json { ignoreUnknownKeys = true }

    override fun syncId(e: RemotePassword) = e.uid
    override fun updatedAt(e: RemotePassword) = e.updatedAt
    override fun isDeleted(e: RemotePassword) = e.deleted

    override fun contentHash(e: RemotePassword): String =
        relayContentHash(canonical(e) + "\u0000deleted=${e.deleted}")

    override fun contentKey(e: RemotePassword): String =
        if (e.isPasskey) {
            "passkey\u0000${e.rpId}\u0000${e.credentialId}"
        } else {
            "password\u0000${e.name}\u0000${e.username}"
        }

    override fun rebind(e: RemotePassword, syncId: String) = e.copy(uid = syncId)
    override fun withDeleted(e: RemotePassword, deleted: Boolean) = e.copy(deleted = deleted)
    override fun withUpdatedAt(e: RemotePassword, updatedAt: Long) = e.copy(updatedAt = updatedAt)

    /**
     * Canonical JSON with identity and timestamps stripped, so the hash is
     * comparable across sides and stable across sync cycles.
     */
    fun canonical(e: RemotePassword): String = json.encodeToString(
        e.copy(uid = "", updatedAt = 0L, deleted = false),
    )

    /**
     * True when this envelope is an explicit deletion marker. The merge treats
     * ONLY such envelopes as deletes; every other envelope is live data even
     * when the referenced row is otherwise unknown.
     */
    fun isTombstoneEnvelope(e: RemotePassword): Boolean = e.deleted

    fun toJson(e: RemotePassword): String = json.encodeToString(e)

    fun fromJson(raw: String): RemotePassword? = runCatching {
        json.decodeFromString<RemotePassword>(raw)
    }.getOrNull()
}
