package com.vayunmathur.taxi.pure

import com.vayunmathur.taxi.network.lyft.ProtoMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the minimal protobuf reader through production ProtoMessage:
 * varint, fixed64 double, length-delimited strings and sub-messages,
 * wrapper accessors, last-one-wins and missing-field nulls.
 *
 * ProtoMessage is internal to the taxi module, so the same-module unit
 * test builds raw wire bytes by hand and reads them back.
 */
class ProtoMessageTest {
    private fun varintBytes(value: Long): ByteArray {
        val out = mutableListOf<Byte>()
        var v = value
        while (true) {
            var b = (v and 0x7FL).toInt()
            v = v ushr 7
            if (v != 0L) b = b or 0x80
            out.add(b.toByte())
            if (v == 0L) break
        }
        return out.toByteArray()
    }

    private fun record(field: Int, wire: Int, payload: ByteArray): ByteArray {
        val tag = ((field shl 3) or wire).toLong()
        return varintBytes(tag) + payload
    }

    private fun varintField(field: Int, value: Long): ByteArray =
        record(field, 0, varintBytes(value))

    private fun stringField(field: Int, value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return record(field, 2, varintBytes(bytes.size.toLong()) + bytes)
    }

    private fun doubleField(field: Int, value: Double): ByteArray {
        val bits = java.lang.Double.doubleToLongBits(value)
        val le = ByteArray(8) { i -> ((bits ushr (8 * i)) and 0xFF).toByte() }
        return record(field, 1, le)
    }

    private fun messageOf(vararg parts: ByteArray): ProtoMessage {
        val bytes = parts.fold(byteArrayOf()) { acc, p -> acc + p }
        return ProtoMessage(bytes, 0, bytes.size)
    }

    @Test
    fun varintRoundTripsSingleAndMultiByte() {
        assertEquals(1L, messageOf(varintField(1, 1L)).varint(1))
        assertEquals(150L, messageOf(varintField(1, 150L)).varint(1))
        assertEquals(300_000L, messageOf(varintField(2, 300_000L)).varint(2))
    }

    @Test
    fun missingFieldReadsNull() {
        val m = messageOf(varintField(1, 7L))
        assertNull(m.varint(2))
        assertNull(m.string(1))
        assertNull(m.double(1))
        assertNull(m.message(1))
        assertEquals(emptyList(), m.messages(1))
    }

    @Test
    fun lastValueWinsForRepeatedField() {
        val m = messageOf(varintField(1, 10L), varintField(1, 20L))
        assertEquals(20L, m.varint(1))
    }

    @Test
    fun stringDecodesUtf8() {
        assertEquals("hi", messageOf(stringField(2, "hi")).string(2))
        assertEquals("Zürich 東京", messageOf(stringField(2, "Zürich 東京")).string(2))
        assertEquals("", messageOf(stringField(2, "")).string(2))
    }

    @Test
    fun fixed64DecodesDouble() {
        assertEquals(1.5, messageOf(doubleField(3, 1.5)).double(3))
        assertEquals(-0.0, messageOf(doubleField(3, -0.0)).double(3))
    }

    @Test
    fun subMessageNestsAndLists() {
        val inner = varintField(1, 9L)
        val outer = messageOf(
            record(5, 2, varintBytes(inner.size.toLong()) + inner),
            record(5, 2, varintBytes(inner.size.toLong()) + inner),
        )
        assertEquals(9L, outer.message(5)?.varint(1))
        assertEquals(2, outer.messages(5).size)
    }

    @Test
    fun wrappedLongReadsFieldOne() {
        val inner = varintField(1, 42L)
        val m = messageOf(record(4, 2, varintBytes(inner.size.toLong()) + inner))
        assertEquals(42L, m.wrappedLong(4))
        assertNull(m.wrappedLong(6))
    }

    @Test
    fun wrappedStringReadsFieldOne() {
        val inner = stringField(1, "USD")
        val m = messageOf(record(8, 2, varintBytes(inner.size.toLong()) + inner))
        assertEquals("USD", m.wrappedString(8))
    }

    @Test
    fun wrappedBoolTreatsNonZeroAsTrue() {
        val t = messageOf(record(3, 2, varintBytes(2L) + varintField(1, 1L)))
        val f = messageOf(record(3, 2, varintBytes(2L) + varintField(1, 0L)))
        assertEquals(true, t.wrappedBool(3))
        assertEquals(false, f.wrappedBool(3))
        assertNull(messageOf().wrappedBool(3))
    }

    @Test
    fun emptyBufferReadsAllNulls() {
        val m = messageOf()
        assertNull(m.varint(1))
        assertNull(m.string(1))
        assertNull(m.double(1))
        assertNull(m.message(1))
    }
}
