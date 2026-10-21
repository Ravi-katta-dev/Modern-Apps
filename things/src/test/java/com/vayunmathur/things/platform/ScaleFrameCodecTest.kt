package com.vayunmathur.things.platform

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScaleFrameCodecTest {
    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-9) {
        assertTrue(kotlin.math.abs(expected - actual) <= tolerance, "expected $expected but was $actual")
    }

    @Test
    fun buildFrameLayoutsHeaderPayloadAndChecksum() {
        val frame = buildFrame(0x13, 0x01, 0x02)
        assertContentEquals(byteArrayOf(0x13, 0x05, 0x01, 0x02, 0x1B), frame)
    }

    @Test
    fun buildFrameWithEmptyPayloadKeepsHeaderAndChecksum() {
        val frame = buildFrame(0x22, 0x05)
        assertContentEquals(byteArrayOf(0x22, 0x04, 0x05, 0x2B), frame)
    }

    @Test
    fun buildFrameLengthByteMatchesActualSize() {
        val frame = buildFrame(0x13, 0x01, 0x02, 0x03, 0x04)
        assertEquals(frame.size, frame[1].toInt())
        var sum = 0
        for (i in 0 until frame.size - 1) sum += frame[i].toInt()
        assertEquals(sum.toByte(), frame[frame.size - 1])
    }

    @Test
    fun buildCmdWithTypeMatchesBuildFrame() {
        assertContentEquals(buildFrame(0x13, 0x02, 0x07), buildCmdWithType(0x13, 0x02, 0x07))
    }

    @Test
    fun timePayloadIsLittleEndianSecondsSince2000Epoch() {
        assertContentEquals(intArrayOf(0, 0, 0, 0), timePayload(946684800000L))
        assertContentEquals(intArrayOf(1, 0, 0, 0), timePayload(946684801000L))
        assertContentEquals(intArrayOf(0x04, 0x03, 0x02, 0x01), timePayload(963593860000L))
    }

    @Test
    fun timePayloadBeforeEpochWrapsTwosComplement() {
        assertContentEquals(intArrayOf(0xFF, 0xFF, 0xFF, 0xFF), timePayload(946684799000L))
    }

    @Test
    fun twoByteIntIsBigEndianUnsigned() {
        assertEquals(500, twoByteInt(0x01.toByte(), 0xF4.toByte()))
        assertEquals(65535, twoByteInt(0xFF.toByte(), 0xFF.toByte()))
        assertEquals(0, twoByteInt(0x00.toByte(), 0x00.toByte()))
    }

    @Test
    fun decodeWeightDividesAndRescales() {
        assertClose(70.0, decodeWeight(700, 10.0))
        assertClose(70.0, decodeWeight(7000, 10.0))
        assertEquals(0.0, decodeWeight(0, 10.0))
    }

    @Test
    fun decodeWeightBoundaryAt300Kg() {
        assertClose(300.0, decodeWeight(3000, 10.0))
        assertClose(30.01, decodeWeight(3001, 10.0))
    }

    @Test
    fun decodeWeightByMultiplicationMatchesDivisionPath() {
        assertClose(70.0, decodeWeightByMultiplication(700, 0.1))
        assertClose(70.0, decodeWeightByMultiplication(7000, 0.1))
        assertEquals(0.0, decodeWeightByMultiplication(0, 0.1))
    }
}
