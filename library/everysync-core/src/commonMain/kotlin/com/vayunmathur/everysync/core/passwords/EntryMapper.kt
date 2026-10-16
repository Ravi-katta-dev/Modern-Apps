package com.vayunmathur.everysync.core.passwords

import com.vayunmathur.everysync.core.sync.SyncDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The single Password/Passkey <-> field-map conversion, shared by the one-shot
 * backup format and the bidirectional sync.
 *
 * The mapping must round-trip: `toFields(fromFields(f))` has to be stable, because the
 * merge compares content hashes computed from it on both sides.
 *
 * KDBX interop contract: keep every key name verbatim for desktop file-sync compat.
 */
object EntryMapper {
    const val FIELD_TYPE = "_Type"
    const val FIELD_SYNC_ID = "_SyncId"
    const val FIELD_MODIFIED = "_Modified"

    /**
     * Own-client deletion marker. When this client deletes an entry it writes
     * a tombstone entry carrying `FIELD_TOMBSTONE = TOMBSTONE_VALUE` (plus the
     * `_SyncId`) and retains it until the delete is acked, instead of dropping
     * the row from the file. Foreign clients cannot write tombstones, so a
     * baseline-present id that is merely ABSENT from the file is ambiguous
     * (format-level: no proof of deletion) and quarantines the sync rather
     * than deleting either side. Only this exact marker value deletes; any
     * other value (including a foreign client's same-named key) is treated as
     * live data and carried through.
     */
    const val FIELD_TOMBSTONE = "_Tombstone"
    const val TOMBSTONE_VALUE = "deleted"

    private const val FIELD_SIGN_COUNT = "_SignCount"
    private const val FIELD_CREATED = "_Created"
    private const val FIELD_LAST_USED = "_LastUsed"
    private const val FIELD_LINKED_PASSWORD = "_LinkedPassword"

    /**
     * Every key this mapper can emit. Keys outside this set belong to whatever client
     * wrote the vault and are carried through untouched on push.
     */
    val OWNED_KEYS = setOf(
        "Title", "UserName", "Email", "Password", "Notes", "URL", "Websites", "otp", "TOTP Seed",
        FIELD_TYPE, FIELD_SYNC_ID, FIELD_MODIFIED, FIELD_TOMBSTONE,
        FIELD_SIGN_COUNT, FIELD_CREATED, FIELD_LAST_USED, FIELD_LINKED_PASSWORD,
        "KPEX_PASSKEY_USERNAME", "KPEX_PASSKEY_PRIVATE_KEY_PEM", "KPEX_PASSKEY_CREDENTIAL_ID",
        "KPEX_PASSKEY_USER_HANDLE", "KPEX_PASSKEY_RELYING_PARTY",
    )

    /** True when this entry is an own-client deletion marker (see FIELD_TOMBSTONE). */
    fun isTombstoneEntry(entry: Map<String, String>): Boolean =
        entry[FIELD_TOMBSTONE] == TOMBSTONE_VALUE &&
            entry[FIELD_SYNC_ID]?.isNotBlank() == true

    fun isPasskeyEntry(entry: Map<String, String>): Boolean =
        entry.keys.any { it.startsWith("KPEX_PASSKEY_") }

    fun toFields(
        name: String,
        username: String,
        email: String,
        password: String,
        note: String,
        websites: List<String>,
        totpSecret: String?,
        syncId: String,
        updatedAt: Long,
    ): Map<String, String> = buildMap {
        put("Title", name)
        put("UserName", username)
        put("Email", email)
        put("Password", password)
        put("Notes", note)
        if (websites.isNotEmpty()) {
            put("URL", websites.first())
            if (websites.size > 1) put("Websites", websites.joinToString("\n"))
        }
        totpSecret?.takeIf { it.isNotBlank() }?.let { put("otp", "otpauth://totp/?secret=$it") }
        put(FIELD_TYPE, "password")
        put(FIELD_SYNC_ID, syncId)
        put(FIELD_MODIFIED, updatedAt.toString())
    }

    /** Content fingerprint ignoring identity, timestamps and tombstone markers. */
    fun contentHash(fields: Map<String, String>): String {
        val canonical = fields.entries
            .filter { it.key != FIELD_SYNC_ID && it.key != FIELD_MODIFIED && it.key != FIELD_TOMBSTONE }
            .sortedBy { it.key }
            .joinToString("") { "${it.key}=${it.value}\n" }
        return SyncDigest.sha256Hex(canonical.encodeToByteArray())
    }

    /** Match key used to adopt pre-existing vault entries on the very first sync. */
    fun passwordContentKey(name: String, username: String): String = "pw\u0000$name\u0000$username"

    fun passkeyContentKey(rpId: String, credentialId: String): String = "pk\u0000$rpId\u0000$credentialId"

    private val json = Json { ignoreUnknownKeys = true }

    /** Canonical JSON helper for callers that hash DTO maps through JSON. */
    fun fieldsJson(fields: Map<String, String>): String = json.encodeToString(fields)
}
