package com.vayunmathur.things.platform

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BottleHexCodecTest {
    @Test
    fun beIntReadsBigEndianUnsigned() {
        assertEquals(500, beInt(byteArrayOf(0x01.toByte(), 0xF4.toByte()), 0))
        assertEquals(65535, beInt(byteArrayOf(0xFF.toByte(), 0xFF.toByte()), 0))
        assertEquals(0x0203, beInt(byteArrayOf(0x01.toByte(), 0x02.toByte(), 0x03.toByte()), 1))
    }

    @Test
    fun byteArrayToHexUsesLowercasePaddedBytes() {
        assertEquals("000fff", byteArrayOf(0x00.toByte(), 0x0F.toByte(), 0xFF.toByte()).toHex())
        assertEquals("", byteArrayOf().toHex())
    }

    @Test
    fun toHexPadsWithLeadingZeros() {
        assertEquals("00", toHex(0, 2))
        assertEquals("ff", toHex(255, 2))
        assertEquals("0010", toHex(16, 4))
        assertEquals("1234", toHex(0x1234, 4))
    }

    @Test
    fun hexToByteArrayDecodesAndIgnoresWhitespace() {
        assertContentEquals(byteArrayOf(0x00.toByte(), 0xFF.toByte()), hexToByteArray("00ff"))
        assertContentEquals(byteArrayOf(0x00.toByte(), 0xFF.toByte()), hexToByteArray("00 ff"))
        assertContentEquals(byteArrayOf(), hexToByteArray(""))
    }

    @Test
    fun hexCodecRoundTrips() {
        val raw = byteArrayOf(0x52.toByte(), 0x50.toByte(), 0x00.toByte(), 0x31.toByte())
        assertContentEquals(raw, hexToByteArray(raw.toHex()))
    }

    @Test
    fun volumePctMatchesWaterHFillMath() {
        assertEquals(0, volumePct(0))
        assertEquals(50, volumePct(265))
        assertEquals(100, volumePct(530))
        assertEquals(0, volumePct(-10))
        assertEquals(100, volumePct(1000))
        assertEquals(0, volumePct(1))
    }

    @Test
    fun chargingStatesHoldChargingAndCharged() {
        assertTrue(1 in CHARGING_STATES)
        assertTrue(2 in CHARGING_STATES)
        assertEquals(2, CHARGING_STATES.size)
    }

    @Test
    fun buildSyncCommandKeepsFixedFraming() {
        val cmd = buildSyncCommand()
        assertTrue(cmd.startsWith(SYNC_HEAD + SYNC_GOAL + SYNC_MID), "unexpected head: $cmd")
        assertTrue(cmd.endsWith(SYNC_TAIL + SYNC_REMINDER), "unexpected tail: $cmd")
        assertEquals(48, cmd.length)
    }
}
