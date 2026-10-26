package com.vayunmathur.share.domain.airdrop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CpioTest {

    @Test
    fun `odc header matches the portable format`() {
        val out = ByteArrayOutputStream()
        Cpio.Writer(out).use { it.file("./a.txt", 3, ByteArrayInputStream("hey".toByteArray()), mtimeSeconds = 0) }
        val header = String(out.toByteArray(), 0, 76, Charsets.US_ASCII)
        assertEquals(
            "070707" + "000000" + "000001" + "100644" + "000000" + "000000" + "000001" + "000000" +
                "00000000000" + "000010" + "00000000003",
            header,
        )
        assertEquals("./a.txt\u0000hey", String(out.toByteArray(), 76, 11, Charsets.US_ASCII))
    }

    @Test
    fun `round trips directories and files`() {
        val big = ByteArray(200_000) { (it * 31).toByte() }
        val out = ByteArrayOutputStream()
        Cpio.Writer(out).use { w ->
            w.directory("./photos")
            w.file("./photos/big.bin", big.size.toLong(), ByteArrayInputStream(big))
            w.file("./empty", 0, ByteArrayInputStream(ByteArray(0)))
        }
        val reader = Cpio.Reader(ByteArrayInputStream(out.toByteArray()))

        val (dir, _) = reader.next()!!
        assertEquals("./photos", dir.name)
        assertTrue(dir.isDirectory)

        val (file, content) = reader.next()!!
        assertEquals("./photos/big.bin", file.name)
        assertTrue(file.isFile)
        assertArrayEquals(big, content.readBytes())

        val (empty, emptyContent) = reader.next()!!
        assertEquals("./empty", empty.name)
        assertEquals(-1, emptyContent.read())

        assertNull(reader.next())
    }

    @Test
    fun `next skips content the caller did not read`() {
        val out = ByteArrayOutputStream()
        Cpio.Writer(out).use { w ->
            w.file("./one", 5, ByteArrayInputStream("12345".toByteArray()))
            w.file("./two", 2, ByteArrayInputStream("ab".toByteArray()))
        }
        val reader = Cpio.Reader(ByteArrayInputStream(out.toByteArray()))
        reader.next()
        val (two, content) = reader.next()!!
        assertEquals("./two", two.name)
        assertEquals("ab", content.readBytes().decodeToString())
    }

    @Test
    fun `reads newc with alignment padding`() {
        fun newc(name: String, mode: Int, data: ByteArray): ByteArray {
            val nameBytes = name.toByteArray() + 0.toByte()
            val fields = listOf(1, mode, 0, 0, 1, 0, data.size, 0, 0, 0, 0, nameBytes.size, 0)
            val header = "070701" + fields.joinToString("") { "%08x".format(it) }
            val out = ByteArrayOutputStream()
            out.write(header.toByteArray())
            out.write(nameBytes)
            while (out.size() % 4 != 0) out.write(0)
            out.write(data)
            while (out.size() % 4 != 0) out.write(0)
            return out.toByteArray()
        }
        val archive = newc("./x", Cpio.MODE_FILE, "hello".toByteArray()) +
            newc("TRAILER!!!", 0, ByteArray(0))
        val reader = Cpio.Reader(ByteArrayInputStream(archive))
        val (entry, content) = reader.next()!!
        assertEquals("./x", entry.name)
        assertFalse(entry.isDirectory)
        assertEquals("hello", content.readBytes().decodeToString())
        assertNull(reader.next())
    }

    @Test
    fun `rejects a bad magic and a truncated entry`() {
        assertThrows(Cpio.FormatException::class.java) {
            Cpio.Reader(ByteArrayInputStream("999999".toByteArray() + ByteArray(80))).next()
        }
        val out = ByteArrayOutputStream()
        Cpio.Writer(out).use { it.file("./f", 10, ByteArrayInputStream(ByteArray(10))) }
        val truncated = out.toByteArray().copyOf(76 + 4 + 3)
        val (_, content) = Cpio.Reader(ByteArrayInputStream(truncated)).next()!!
        assertThrows(java.io.EOFException::class.java) { content.readBytes() }
    }
}
