package com.vayunmathur.securemail.network

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailSocketTest {

    @Test
    fun subFrameRoundTripShape() {
        val frame = MailSocket.subFrame("Alice@MAEMAIL.ORG", "ARMOR")
        assertEquals(MailSocket.OP_SUB, frame[0])
        val addrLen = MailSocket.readU16(frame, 1)
        assertEquals("alice@maemail.org", frame.copyOfRange(3, 3 + addrLen).toString(Charsets.UTF_8))
        assertEquals("ARMOR", frame.copyOfRange(3 + addrLen, frame.size).toString(Charsets.UTF_8))
    }

    @Test
    fun pubFrameFlags() {
        val enc = MailSocket.pubFrame("b@maemail.org", byteArrayOf(1, 2, 3), unencrypted = false)
        assertEquals(MailSocket.OP_PUB, enc[0])
        assertEquals(0, enc[1])
        val plain = MailSocket.pubFrame("b@maemail.org", byteArrayOf(1, 2, 3), unencrypted = true)
        assertEquals(MailSocket.FLAG_PLAIN, plain[1])
    }

    @Test
    fun getKeyFrameShape() {
        val frame = MailSocket.getKeyFrame("B@Proton.me")
        assertEquals(MailSocket.OP_GETKEY_REQ, frame[0])
        val len = MailSocket.readU16(frame, 1)
        assertEquals("b@proton.me", frame.copyOfRange(3, 3 + len).toString(Charsets.UTF_8))
    }

    @Test
    fun parseMsgRoundTrip() {
        val id = "abc-123"
        val idBytes = id.toByteArray(Charsets.UTF_8)
        val raw = byteArrayOf(9, 8, 7)
        val buf = ByteArray(1 + 1 + 4 + idBytes.size + raw.size)
        buf[0] = MailSocket.OP_MSG
        buf[1] = 0
        buf[2] = 0; buf[3] = 0; buf[4] = 0; buf[5] = idBytes.size.toByte()
        idBytes.copyInto(buf, 6)
        raw.copyInto(buf, 6 + idBytes.size)
        val parsed = MailSocket.parseMsg(buf)!!
        assertEquals(id, parsed.first)
        assertEquals(false, parsed.second)
        assertContentEquals(raw, parsed.third)
    }

    @Test
    fun parseMsgRejectsTruncated() {
        assertNull(MailSocket.parseMsg(byteArrayOf(MailSocket.OP_MSG)))
        assertNull(MailSocket.parseMsg(byteArrayOf(MailSocket.OP_MSG, 0, 0)))
    }

    @Test
    fun parseKeyRespFoundAndMissing() {
        val addr = "a@maemail.org".toByteArray(Charsets.UTF_8)
        val key = "ARMOR".toByteArray(Charsets.UTF_8)
        val found = ByteArray(1 + 1 + 2 + addr.size + key.size)
        found[0] = MailSocket.OP_GETKEY_RESP
        found[1] = MailSocket.KEY_FOUND.toByte()
        MailSocket.putU16(found, 2, addr.size)
        addr.copyInto(found, 4)
        key.copyInto(found, 4 + addr.size)
        val (parsedAddr, parsedKey) = MailSocket.parseKeyResp(found)!!
        assertEquals("a@maemail.org", parsedAddr)
        assertEquals("ARMOR", parsedKey)
        val missing = ByteArray(1 + 1 + 2 + addr.size)
        missing[0] = MailSocket.OP_GETKEY_RESP
        missing[1] = MailSocket.KEY_NONE.toByte()
        MailSocket.putU16(missing, 2, addr.size)
        addr.copyInto(missing, 4)
        val (_, missingKey) = MailSocket.parseKeyResp(missing)!!
        assertNull(missingKey)
    }

    @Test
    fun parseFetchedRoundTrip() {
        val frame = ByteArray(1 + 2 + 3 + 2)
        frame[0] = MailSocket.OP_FETCH_RESP
        MailSocket.putU16(frame, 1, 3)
        frame[3] = 'a'.code.toByte(); frame[4] = 'b'.code.toByte(); frame[5] = 'c'.code.toByte()
        frame[6] = 1; frame[7] = 2
        val (id, raw) = MailSocket.parseFetched(frame)!!
        assertEquals("abc", id)
        assertTrue(raw.contentEquals(byteArrayOf(1, 2)))
    }

    @Test
    fun u16RoundTrip() {
        val buf = ByteArray(4)
        MailSocket.putU16(buf, 1, 0xABCD)
        assertEquals(0xABCD, MailSocket.readU16(buf, 1))
    }
}
