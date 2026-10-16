package com.vayunmathur.everysync.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * NIST FIPS 180-4 SHA-256 vectors for the pure-Kotlin [SyncDigest].
 * `relayContentHash` output must match `sha256sum` on every target.
 */
class SyncDigestTest {

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { it.toInt().and(0xff).toString(16).padStart(2, '0') }

    @Test
    fun emptyString() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            hex(SyncDigest.sha256(ByteArray(0))),
        )
    }

    @Test
    fun abc() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hex(SyncDigest.sha256("abc".encodeToByteArray())),
        )
    }

    @Test
    fun twoBlockMessage() {
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hex(SyncDigest.sha256("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray())),
        )
    }

    @Test
    fun relayHashMatchesShell() {
        // Cross-check: `echo -n "hello" | sha256sum` =
        // 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            relayContentHash("hello"),
        )
    }
}
