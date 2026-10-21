package com.vayunmathur.everysync.sync.adapters

import com.vayunmathur.everysync.data.RemoteNote
import com.vayunmathur.everysync.data.RemoteTodo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncKindTest {

    private fun todo(title: String, updatedAt: Long = 100L, position: Double = 0.0): RemoteTodo =
        RemoteTodo(uid = "t1", title = title, position = position, createdAt = 7L, updatedAt = updatedAt)

    private fun note(title: String, position: Double = 0.0, blocksJson: String? = null): RemoteNote =
        RemoteNote(uid = "n1", title = title, content = "body", blocksJson = blocksJson, position = position)

    // ---- channels and content keys ----

    @Test
    fun todoChannelPrefixesTheVaultId() {
        assertEquals("todos:vault-1", TodoKind.channel("vault-1"))
    }

    @Test
    fun todoContentKeyUsesTitleAndCreatedAt() {
        val first = todo("Shop").copy(createdAt = 1L)
        val sameKey = todo("Shop").copy(createdAt = 1L)
        val otherTitle = todo("Work").copy(createdAt = 1L)
        val otherTime = todo("Shop").copy(createdAt = 2L)
        assertEquals(TodoKind.contentKey(first), TodoKind.contentKey(sameKey))
        assertTrue(TodoKind.contentKey(first) != TodoKind.contentKey(otherTitle))
        assertTrue(TodoKind.contentKey(first) != TodoKind.contentKey(otherTime))
    }

    @Test
    fun passwordContentKeyDistinguishesPasswordsFromPasskeys() {
        val password = PasswordKind.password(uid = "a", name = "Bank", username = "ann")
        val passkey = PasswordKind.passkey(uid = "b", rpId = "example.com", credentialId = "cred-1")
        val otherPasskey = PasswordKind.passkey(uid = "c", rpId = "example.com", credentialId = "cred-2")
        assertTrue(PasswordKind.contentKey(password).startsWith("password"))
        assertTrue(PasswordKind.contentKey(passkey).startsWith("passkey"))
        assertTrue(PasswordKind.contentKey(passkey) != PasswordKind.contentKey(otherPasskey))
        assertTrue(PasswordKind.contentKey(password) != PasswordKind.contentKey(passkey))
    }

    // ---- canonical forms and hashes ----

    @Test
    fun todoCanonicalIgnoresIdentityPositionAndTimestamps() {
        val base = todo("Shop", updatedAt = 100L, position = 1.0)
        val variant = base.copy(uid = "other", position = 9.0, updatedAt = 999L)
        assertEquals(TodoKind.canonical(base), TodoKind.canonical(variant))
        assertEquals(TodoKind.contentHash(base), TodoKind.contentHash(variant))
    }

    @Test
    fun todoContentHashChangesOnRealEdits() {
        val base = todo("Shop")
        assertTrue(TodoKind.contentHash(base) != TodoKind.contentHash(base.copy(title = "Mall")))
        assertTrue(TodoKind.contentHash(base) != TodoKind.contentHash(base.copy(done = true)))
        assertTrue(TodoKind.contentHash(base) != TodoKind.contentHash(base.copy(deleted = true)))
    }

    @Test
    fun noteCanonicalIgnoresReorders() {
        val base = note("Ideas", position = 1.0)
        val reordered = base.copy(position = 8.5)
        assertEquals(NoteKind.canonical(base), NoteKind.canonical(reordered))
        assertEquals(NoteKind.contentHash(base), NoteKind.contentHash(reordered))
    }

    @Test
    fun passwordTombstoneEnvelopeMatchesTheDeletedFlag() {
        val live = PasswordKind.password(uid = "a", name = "Bank")
        val dead = live.copy(deleted = true)
        assertEquals(false, PasswordKind.isTombstoneEnvelope(live))
        assertEquals(true, PasswordKind.isTombstoneEnvelope(dead))
    }

    // ---- JSON envelopes ----

    @Test
    fun todoJsonRoundTripPreservesEveryField() {
        val original = RemoteTodo(
            uid = "t9", title = "Ship", notes = "n", done = true,
            dueAt = 1700000000000L, position = 3.0, createdAt = 11L, updatedAt = 22L,
        )
        assertEquals(original, TodoKind.fromJson(TodoKind.toJson(original)))
    }

    @Test
    fun todoFromJsonReturnsNullForCorruptEnvelopes() {
        assertNull(TodoKind.fromJson("not json"))
        assertNull(TodoKind.fromJson(""))
        assertNull(TodoKind.fromJson("""{"uid":42}"""))
    }

    @Test
    fun todoFromJsonIgnoresUnknownKeys() {
        val raw = """{"uid":"t1","title":"Shop","createdAt":7,"updatedAt":100,"future":true}"""
        assertEquals("Shop", TodoKind.fromJson(raw)?.title)
    }

    @Test
    fun passwordJsonRoundTripKeepsBothHalves() {
        val password = PasswordKind.password(uid = "a", name = "Bank", username = "ann", updatedAt = 5L)
        val passkey = PasswordKind.passkey(
            uid = "b", rpId = "example.com", credentialId = "cred-1", updatedAt = 6L,
        )
        assertEquals(password, PasswordKind.fromJson(PasswordKind.toJson(password)))
        assertEquals(passkey, PasswordKind.fromJson(PasswordKind.toJson(passkey)))
        assertNull(PasswordKind.fromJson("{{{"))
    }

    // ---- note image references ----

    @Test
    fun noteImageNamesExtractsDistinctNamesInOrder() {
        val blocks = """{"blocks":[{"type":"text"},{"type":"image","fileName":"a.png"},""" +
            """{"type":"image","fileName":"b.png"},{"type":"image","fileName":"a.png"}]}"""
        assertEquals(listOf("a.png", "b.png"), NoteKind.imageNames(blocks))
    }

    @Test
    fun noteImageNamesIsEmptyWithoutReferences() {
        assertTrue(NoteKind.imageNames(null).isEmpty())
        assertTrue(NoteKind.imageNames("").isEmpty())
        assertTrue(NoteKind.imageNames("   ").isEmpty())
        assertTrue(NoteKind.imageNames("""{"blocks":[]}""").isEmpty())
        assertTrue(NoteKind.imageNames("""{"blocks":[{"type":"text"}]}""").isEmpty())
    }
}
