package com.vayunmathur.desktop.passwords

import com.vayunmathur.everysync.core.data.RemotePassword
import com.vayunmathur.everysync.core.passwords.EntryMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * KDBX interchange round-trip over the VERBATIM shared [EntryMapper] field
 * map: every key name asserted here is the shared constant (not a copy), so
 * renaming a key in either layer breaks this test instead of silently
 * forking the merge hashes across sides.
 *
 * Covers: password-half round-trip (incl. multi-website + TOTP seed),
 * passkey-half round-trip (incl. linked-password id), tombstone import
 * (deletion marker deletes the vault row; non-marker `_Tombstone` values are
 * live data), and sync-id/modified identity markers.
 */
class KdbxRoundTripTest {

    @Test
    fun `password round-trips through verbatim EntryMapper keys`() {
        val entry = RemotePassword(
            uid = "pw1",
            name = "Bank",
            username = "ada",
            email = "ada@example.com",
            password = "s3cret",
            note = "vault note",
            totpSecret = "JBSWY3DPEHPK3PXP",
            websites = listOf("https://bank.example", "https://bank.example/mobile"),
            updatedAt = 1_700_000_000_000L,
        )
        val fields = KdbxCompat.toFields(entry)

        // Verbatim key assertions: these ARE the shared constants.
        assertEquals(entry.uid, fields[EntryMapper.FIELD_SYNC_ID])
        assertEquals(entry.updatedAt.toString(), fields[EntryMapper.FIELD_MODIFIED])
        assertEquals("password", fields[EntryMapper.FIELD_TYPE])
        assertEquals("Bank", fields["Title"])
        assertEquals("ada", fields["UserName"])
        assertEquals("ada@example.com", fields["Email"])
        assertEquals("s3cret", fields["Password"])
        assertEquals("vault note", fields["Notes"])
        assertEquals("https://bank.example", fields["URL"])
        // Verbatim EntryMapper shape: Websites carries ALL sites joined
        // (first included — URL doubles as the primary).
        assertEquals("https://bank.example\nhttps://bank.example/mobile", fields["Websites"])
        assertEquals("otpauth://totp/?secret=JBSWY3DPEHPK3PXP", fields["otp"])
        // Only owned keys are written.
        assertTrue(fields.keys.all { it in EntryMapper.OWNED_KEYS })

        val back = KdbxCompat.fromFields(fields, now = 0L)!!
        assertEquals(entry.uid, back.uid)
        assertEquals(entry.name, back.name)
        assertEquals(entry.username, back.username)
        assertEquals(entry.email, back.email)
        assertEquals(entry.password, back.password)
        assertEquals(entry.note, back.note)
        assertEquals(entry.totpSecret, back.totpSecret)
        // Verbatim EntryMapper shape: Websites = ALL sites joined, so the
        // round-trip restores first→URL + full list→Websites losslessly.
        assertEquals(listOf("https://bank.example", "https://bank.example/mobile"), back.websites)
        assertEquals(entry.updatedAt, back.updatedAt)
        assertFalse(back.isPasskey)
    }

    @Test
    fun `passkey round-trips including linked password id`() {
        val entry = RemotePassword(
            uid = "pk1",
            isPasskey = true,
            rpId = "example.com",
            rpName = "Example",
            credentialId = "cred-1",
            userId = "user-1",
            userName = "ada",
            userDisplayName = "Ada",
            privateKeyB64 = "a2V5",
            linkedPasswordSyncId = "pw1",
            updatedAt = 2_000L,
        )
        val fields = KdbxCompat.toFields(entry)
        assertEquals("passkey", fields[EntryMapper.FIELD_TYPE])
        assertEquals("pw1", fields["_LinkedPassword"])
        assertTrue(fields.keys.all { it in EntryMapper.OWNED_KEYS })

        val back = KdbxCompat.fromFields(fields, now = 0L)!!
        assertTrue(back.isPasskey)
        assertEquals("example.com", back.rpId)
        assertEquals("cred-1", back.credentialId)
        assertEquals("user-1", back.userId)
        assertEquals("a2V5", back.privateKeyB64)
        assertEquals("pw1", back.linkedPasswordSyncId)
        assertEquals(2_000L, back.updatedAt)
    }

    @Test
    fun `csv export and import preserve the field map`() {
        val entries = listOf(
            RemotePassword(uid = "a", name = "A, quoted", username = "u", password = "p", updatedAt = 1L),
            RemotePassword(uid = "b", name = "B", username = "v", password = "q", updatedAt = 2L),
        )
        val csv = KdbxCompat.exportCsv(entries)
        val maps = KdbxCompat.importCsv(csv)
        assertEquals(2, maps.size)
        assertEquals("A, quoted", maps[0]["Title"])
        val back = maps.map { KdbxCompat.fromFields(it, now = 0L) }
        // CSV carries no _SyncId column: rows without one are skipped (null),
        // never adopted as new — silent adoption would fork sync ids.
        assertTrue(back.all { it == null })
    }

    @Test
    fun `json export and import round-trip`() {
        val entries = listOf(
            RemotePassword(uid = "a", name = "A", username = "u", password = "p", updatedAt = 1L),
            RemotePassword(
                uid = "pk", isPasskey = true, rpId = "r", credentialId = "c",
                updatedAt = 2L,
            ),
        )
        val maps = KdbxCompat.importJson(KdbxCompat.exportJson(entries))
        assertEquals(2, maps.size)
        val back = maps.mapNotNull { KdbxCompat.fromFields(it, now = 0L) }
        assertEquals(listOf("a", "pk"), back.map { it.uid })
    }

    @Test
    fun `tombstone import deletes the vault row`() {
        val tombstone = mapOf(
            EntryMapper.FIELD_SYNC_ID to "dead",
            EntryMapper.FIELD_TOMBSTONE to EntryMapper.TOMBSTONE_VALUE,
        )
        assertTrue(KdbxCompat.isTombstone(tombstone))
        assertEquals("dead", KdbxCompat.tombstoneSyncId(tombstone))
        assertNull(KdbxCompat.fromFields(tombstone, now = 0L))

        // Any other _Tombstone value is live data, never a delete.
        val live = mapOf(
            EntryMapper.FIELD_SYNC_ID to "live",
            EntryMapper.FIELD_TOMBSTONE to "foreign-value",
            "Title" to "T",
        )
        assertFalse(KdbxCompat.isTombstone(live))
        assertNull(KdbxCompat.tombstoneSyncId(live))
    }
}
