package com.vayunmathur.desktop.passwords

import com.vayunmathur.everysync.core.data.RemotePassword
import com.vayunmathur.everysync.core.passwords.EntryMapper
import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * KeePass-style CSV/JSON file-sync interchange using the shared core
 * [EntryMapper] field map, verbatim.
 *
 * KeePassXC-compat contract (no new crypto):
 * - Every key name below is exactly the shared [EntryMapper] key — including
 *   the `_Type` / `_SyncId` / `_Modified` identity markers and the
 *   `KPEX_PASSKEY_*` passkey half. Renaming any key here breaks the merge
 *   hash comparison across sides, so keep names verbatim and import new keys
 *   only by extending the shared mapper first.
 * - Only [EntryMapper.OWNED_KEYS] are written; foreign keys encountered on
 *   import are carried through into the vault rows untouched.
 * - This layer carries field maps only — plaintext export with NO KDBX
 *   encryption. File protection stays out of scope for Phase 2a.
 * - Tombstone imports (`_Tombstone = deleted` with a `_SyncId`) delete the
 *   matching vault row; any other value under `_Tombstone` is live data.
 */
object KdbxCompat {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    // Verbatim KeePassXC-compat column names (shared EntryMapper keys).
    const val COL_TITLE = "Title"
    const val COL_USERNAME = "UserName"
    const val COL_EMAIL = "Email"
    const val COL_PASSWORD = "Password"
    const val COL_NOTES = "Notes"
    const val COL_URL = "URL"
    const val COL_WEBSITES = "Websites"
    const val COL_OTP = "otp"
    const val COL_TOTP_SEED = "TOTP Seed"

    /** KeePassXC CSV header order for the password half. */
    val CSV_HEADERS = listOf(COL_TITLE, COL_USERNAME, COL_EMAIL, COL_PASSWORD, COL_NOTES, COL_URL, COL_OTP)

    /** A vault row as a field map (KeePass entry shape). Foreign keys ride along. */
    fun toFields(entry: RemotePassword): Map<String, String> = buildMap {
        if (entry.isPasskey) {
            put(COL_TITLE, entry.rpName)
            put(COL_USERNAME, entry.userDisplayName)
            put(COL_URL, entry.rpId)
            put(EntryMapper.FIELD_TYPE, "passkey")
            put("KPEX_PASSKEY_USERNAME", entry.userName)
            put("KPEX_PASSKEY_PRIVATE_KEY_PEM", entry.privateKeyB64)
            put("KPEX_PASSKEY_CREDENTIAL_ID", entry.credentialId)
            put("KPEX_PASSKEY_USER_HANDLE", entry.userId)
            put("KPEX_PASSKEY_RELYING_PARTY", entry.rpId)
            entry.linkedPasswordSyncId?.takeIf { it.isNotBlank() }?.let {
                put("_LinkedPassword", it)
            }
        } else {
            put(COL_TITLE, entry.name)
            put(COL_USERNAME, entry.username)
            put(COL_EMAIL, entry.email)
            put(COL_PASSWORD, entry.password)
            put(COL_NOTES, entry.note)
            if (entry.websites.isNotEmpty()) {
                put(COL_URL, entry.websites.first())
                if (entry.websites.size > 1) put(COL_WEBSITES, entry.websites.joinToString("\n"))
            }
            entry.totpSecret?.takeIf { it.isNotBlank() }?.let {
                put(COL_OTP, "otpauth://totp/?secret=$it")
            }
            put(EntryMapper.FIELD_TYPE, "password")
        }
        put(EntryMapper.FIELD_SYNC_ID, entry.uid)
        put(EntryMapper.FIELD_MODIFIED, entry.updatedAt.toString())
    }

    /** Field map back to a vault row; null skips tombstones the caller already handled. */
    fun fromFields(fields: Map<String, String>, now: Long): RemotePassword? {
        if (EntryMapper.isTombstoneEntry(fields)) return null
        val syncId = fields[EntryMapper.FIELD_SYNC_ID].orEmpty()
        if (syncId.isBlank()) return null
        val updatedAt = fields[EntryMapper.FIELD_MODIFIED]?.toLongOrNull() ?: now
        return if (EntryMapper.isPasskeyEntry(fields)) {
            RemotePassword(
                uid = syncId,
                isPasskey = true,
                rpId = fields["KPEX_PASSKEY_RELYING_PARTY"].orEmpty().ifEmpty { fields[COL_URL].orEmpty() },
                rpName = fields[COL_TITLE].orEmpty(),
                credentialId = fields["KPEX_PASSKEY_CREDENTIAL_ID"].orEmpty(),
                userId = fields["KPEX_PASSKEY_USER_HANDLE"].orEmpty(),
                userName = fields["KPEX_PASSKEY_USERNAME"].orEmpty().ifEmpty { fields[COL_USERNAME].orEmpty() },
                userDisplayName = fields[COL_USERNAME].orEmpty(),
                privateKeyB64 = fields["KPEX_PASSKEY_PRIVATE_KEY_PEM"].orEmpty(),
                linkedPasswordSyncId = fields["_LinkedPassword"]?.takeIf { it.isNotBlank() },
                updatedAt = updatedAt,
            )
        } else {
            val websites = mutableListOf<String>()
            fields[COL_URL]?.takeIf { it.isNotEmpty() }?.let { websites.add(it) }
            fields[COL_WEBSITES]?.split("\n")?.filter { it.isNotBlank() }?.forEach {
                if (it !in websites) websites.add(it)
            }
            val otp = fields[COL_OTP]
            val totpSecret = if (!otp.isNullOrEmpty()) {
                Regex("[?&]secret=([^&]+)").find(otp)?.groupValues?.get(1) ?: otp
            } else {
                fields[COL_TOTP_SEED]?.takeIf { it.isNotBlank() }
            }
            RemotePassword(
                uid = syncId,
                name = fields[COL_TITLE].orEmpty(),
                username = fields[COL_USERNAME].orEmpty(),
                email = fields[COL_EMAIL].orEmpty(),
                password = fields[COL_PASSWORD].orEmpty(),
                note = fields[COL_NOTES].orEmpty(),
                totpSecret = totpSecret,
                websites = websites,
                updatedAt = updatedAt,
            )
        }
    }

    /** True when this imported map is an own-client deletion marker. */
    fun isTombstone(fields: Map<String, String>): Boolean = EntryMapper.isTombstoneEntry(fields)

    fun tombstoneSyncId(fields: Map<String, String>): String? =
        fields[EntryMapper.FIELD_SYNC_ID]?.takeIf { isTombstone(fields) }

    /** Exports rows as KeePassXC-compat CSV (password half columns; passkeys skipped). */
    fun exportCsv(entries: List<RemotePassword>): String {
        val sb = StringBuilder()
        sb.append(CSV_HEADERS.joinToString(",") { csvCell(it) }).append("\n")
        for (entry in entries.filter { !it.isPasskey }) {
            val fields = toFields(entry)
            sb.append(CSV_HEADERS.joinToString(",") { csvCell(fields[it].orEmpty()) }).append("\n")
        }
        return sb.toString()
    }

    /** Imports a KeePassXC-compat CSV into field maps keyed by column name. */
    fun importCsv(text: String): List<Map<String, String>> {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return emptyList()
        val headers = parseCsvLine(lines.first())
        return lines.drop(1).map { line ->
            val cells = parseCsvLine(line)
            headers.mapIndexed { idx, header -> header to (cells.getOrNull(idx).orEmpty()) }.toMap()
        }
    }

    /** Exports rows as a JSON array of field maps (passwords + passkeys). */
    fun exportJson(entries: List<RemotePassword>): String =
        json.encodeToString(entries.map { toFields(it) })

    /** Imports a JSON array of field maps (as produced by [exportJson]). */
    fun importJson(text: String): List<Map<String, String>> = runCatching {
        json.decodeFromString<List<Map<String, String>>>(text)
    }.getOrDefault(emptyList())

    private fun csvCell(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    private fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && line.getOrNull(i + 1) == '"' -> {
                    cur.append('"')
                    i += 2
                }
                c == '"' -> {
                    inQuotes = !inQuotes
                    i++
                }
                c == ',' && !inQuotes -> {
                    out.add(cur.toString())
                    cur.clear()
                    i++
                }
                else -> {
                    cur.append(c)
                    i++
                }
            }
        }
        out.add(cur.toString())
        return out
    }

    /** JVM base64 (mirrors the Android helper's NO_WRAP form). */
    fun b64decode(raw: String): ByteArray =
        runCatching { Base64.getDecoder().decode(raw) }.getOrDefault(ByteArray(0))
}
