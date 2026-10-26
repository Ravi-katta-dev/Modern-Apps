package com.vayunmathur.share.domain.airdrop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DvZipTest {

    @Test
    fun `round trips data spanning several chunks`() {
        val data = ByteArray(DvZip.CHUNK_SIZE * 3 + 1234) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        DvZip.Writer(out).use { it.write(data) }
        assertArrayEquals(data, DvZip.Reader(ByteArrayInputStream(out.toByteArray())).readBytes())
    }

    @Test
    fun `chunks are length-prefixed zlib blocks`() {
        val out = ByteArrayOutputStream()
        DvZip.Writer(out).use { it.write("hello".toByteArray()) }
        val bytes = out.toByteArray()
        val len = java.nio.ByteBuffer.wrap(bytes, 0, 4).int
        assertEquals(bytes.size - 4, len)
        assertEquals(0x78, bytes[4].toInt() and 0xFF)
    }

    @Test
    fun `reads stored chunks and stops at a zero length`() {
        val stream = DvZip.storedHeader(3) + "abc".toByteArray() + byteArrayOf(0, 0, 0, 0) + "junk".toByteArray()
        assertEquals("abc", DvZip.Reader(ByteArrayInputStream(stream)).readBytes().decodeToString())
    }

    @Test
    fun `carries a cpio archive end to end`() {
        val out = ByteArrayOutputStream()
        DvZip.Writer(out).use { dv ->
            Cpio.Writer(dv).use { it.file("./a.txt", 2, ByteArrayInputStream("hi".toByteArray())) }
        }
        val reader = Cpio.Reader(DvZip.Reader(ByteArrayInputStream(out.toByteArray())))
        val (entry, content) = reader.next()!!
        assertEquals("./a.txt", entry.name)
        assertEquals("hi", content.readBytes().decodeToString())
        assertNull(reader.next())
    }

    @Test
    fun `truncated chunk throws`() {
        val out = ByteArrayOutputStream()
        DvZip.Writer(out).use { it.write(ByteArray(1000) { it.toByte() }) }
        val truncated = out.toByteArray().copyOf(out.size() - 5)
        assertThrows(java.io.IOException::class.java) {
            DvZip.Reader(ByteArrayInputStream(truncated)).readBytes()
        }
    }
}
