package com.vayunmathur.camera.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure-domain checks for the FIDO classification: no Android needed. */
class QrClassificationTest {

    @Test
    fun fidoUppercaseMatches() {
        assertTrue(isFidoDeepLink("FIDO:/48219375120864391756028374659102847"))
    }

    @Test
    fun fidoLowercaseMatches() {
        assertTrue(isFidoDeepLink("fido:/48219375120864391756028374659102847"))
    }

    @Test
    fun fidoWithWhitespaceMatches() {
        assertTrue(isFidoDeepLink("  FIDO:/123  "))
    }

    @Test
    fun webUrlIsNotFido() {
        assertFalse(isFidoDeepLink("https://example.com"))
        assertFalse(isFidoDeepLink("example.com"))
    }

    @Test
    fun esimIsNotFido() {
        assertFalse(isFidoDeepLink("LPA:1\$smdp.example\$matching-id"))
    }

    @Test
    fun plainTextIsNotFido() {
        assertFalse(isFidoDeepLink("hello world"))
        assertFalse(isFidoDeepLink(""))
    }

    // --- request-type hint (CBOR map key 5) ---

    @Test
    fun mcHintMeansCreate() {
        assertEquals(FidoRequestType.CREATE, fidoRequestType(fidoUri(hint = "mc")))
    }

    @Test
    fun gaHintMeansSignIn() {
        assertEquals(FidoRequestType.SIGN_IN, fidoRequestType(fidoUri(hint = "ga")))
    }

    @Test
    fun missingHintIsUnknown() {
        assertEquals(FidoRequestType.UNKNOWN, fidoRequestType(fidoUri(hint = null)))
    }

    @Test
    fun garbageBodyIsUnknown() {
        assertEquals(FidoRequestType.UNKNOWN, fidoRequestType("FIDO:/not-digits!!"))
        assertEquals(FidoRequestType.UNKNOWN, fidoRequestType("FIDO:/"))
        assertEquals(FidoRequestType.UNKNOWN, fidoRequestType("https://example.com"))
    }

    /**
     * Builds a minimal `FIDO:/...` URI whose CBOR payload is a map with the
     * required keys 0 (33-byte key) and 1 (16-byte secret) plus an optional
     * key 5 [hint], using the same digit encoding as the real QR format.
     */
    private fun fidoUri(hint: String?): String {
        val cbor = buildCborMap(hint)
        return "FIDO:/" + digitEncode(cbor)
    }

    private fun buildCborMap(hint: String?): ByteArray {
        val out = mutableListOf<Byte>()
        val entries = if (hint == null) 2 else 3
        out.add((0xA0 or entries).toByte())
        // Key 0: 33 zero bytes.
        out.add(0x00.toByte())
        out.add(0x58.toByte())
        out.add(33.toByte())
        repeat(33) { out.add(0) }
        // Key 1: 16 zero bytes.
        out.add(0x01.toByte())
        out.add(0x50.toByte())
        repeat(16) { out.add(0) }
        // Key 5: text hint.
        if (hint != null) {
            out.add(0x05.toByte())
            val bytes = hint.encodeToByteArray()
            out.add((0x60 or bytes.size).toByte())
            bytes.forEach { out.add(it) }
        }
        return out.toByteArray()
    }

    private fun digitEncode(cbor: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (cbor.size - i >= 7) {
            var v = 0L
            for (b in 0 until 7) v = v or ((cbor[i + b].toLong() and 0xFF) shl (8 * b))
            sb.append(v.toString().padStart(17, '0'))
            i += 7
        }
        val remaining = cbor.size - i
        if (remaining > 0) {
            var v = 0L
            for (b in 0 until remaining) v = v or ((cbor[i + b].toLong() and 0xFF) shl (8 * b))
            val digits = intArrayOf(0, 3, 5, 8, 10, 13, 15, 0)[remaining]
            sb.append(v.toString().padStart(digits, '0'))
        }
        return sb.toString()
    }
}
