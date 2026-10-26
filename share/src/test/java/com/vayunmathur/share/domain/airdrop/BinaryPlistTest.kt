package com.vayunmathur.share.domain.airdrop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BinaryPlistTest {

    private fun hex(s: String): ByteArray =
        s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `encodes a one-entry dict byte for byte like plistlib`() {
        val expected = hex(
            "62706c6973743030" + // bplist00
                "d10102" + "5161" + "1001" + // {ref1: ref2}, "a", 1
                "080b0d" + // offset table
                "000000000000" + "01" + "01" +
                "0000000000000003" + "0000000000000000" + "000000000000000f",
        )
        assertArrayEquals(expected, BinaryPlist.encode(mapOf("a" to 1L)))
    }

    @Test
    fun `round trips every AirDrop value type`() {
        val value = linkedMapOf<String, Any?>(
            "SenderComputerName" to "Vayun’s Pixel",
            "ConvertMediaFormats" to false,
            "Big" to 5_000_000_000L,
            "Real" to 1.5,
            "Data" to ByteArray(20) { it.toByte() },
            "Files" to listOf(
                mapOf("FileName" to "a.jpg", "FileSize" to 300L, "FileIsDirectory" to false),
                mapOf("FileName" to "b".repeat(40), "FileSize" to 70_000L),
            ),
            "Empty" to emptyMap<String, Any?>(),
        )
        val decoded = BinaryPlist.decodeDict(BinaryPlist.encode(value))
        assertEquals("Vayun’s Pixel", decoded["SenderComputerName"])
        assertEquals(false, decoded["ConvertMediaFormats"])
        assertEquals(5_000_000_000L, decoded["Big"])
        assertEquals(1.5, decoded["Real"])
        assertArrayEquals(ByteArray(20) { it.toByte() }, decoded["Data"] as ByteArray)
        val files = decoded["Files"] as List<*>
        assertEquals(2, files.size)
        assertEquals("b".repeat(40), (files[1] as Map<*, *>)["FileName"])
        assertEquals(70_000L, (files[1] as Map<*, *>)["FileSize"])
        assertEquals(emptyMap<String, Any?>(), decoded["Empty"])
    }

    @Test
    fun `handles more than 255 objects with two-byte refs`() {
        val list = List(400) { it.toLong() }
        val decoded = BinaryPlist.decode(BinaryPlist.encode(list)) as List<*>
        assertEquals(list, decoded)
    }

    @Test
    fun `rejects garbage and truncated input`() {
        assertThrows(BinaryPlist.FormatException::class.java) { BinaryPlist.decode("hello".toByteArray()) }
        val good = BinaryPlist.encode(mapOf("k" to "v"))
        assertThrows(BinaryPlist.FormatException::class.java) {
            BinaryPlist.decode(good.copyOf(good.size - 3))
        }
    }

    @Test
    fun `decodeDict refuses a non-dict root`() {
        assertThrows(BinaryPlist.FormatException::class.java) {
            BinaryPlist.decodeDict(BinaryPlist.encode(listOf(1L)))
        }
        assertNull(BinaryPlist.decode(BinaryPlist.encode(null)))
        assertTrue(BinaryPlist.decode(BinaryPlist.encode(true)) as Boolean)
    }
}
