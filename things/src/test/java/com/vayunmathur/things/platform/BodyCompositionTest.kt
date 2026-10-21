package com.vayunmathur.things.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BodyCompositionTest {
    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-9) {
        assertTrue(kotlin.math.abs(expected - actual) <= tolerance, "expected $expected but was $actual")
    }

    @Test
    fun clampHeightKeepsInsideAndClampsOutside() {
        assertEquals(40.0, BodyComposition.clampHeight(30.0))
        assertEquals(240.0, BodyComposition.clampHeight(300.0))
        assertEquals(175.0, BodyComposition.clampHeight(175.0))
    }

    @Test
    fun clampAgeKeepsInsideAndClampsOutside() {
        assertEquals(3, BodyComposition.clampAge(1))
        assertEquals(80, BodyComposition.clampAge(100))
        assertEquals(30, BodyComposition.clampAge(30))
    }

    @Test
    fun clampResistanceKeepsInsideAndClampsOutside() {
        assertEquals(100, BodyComposition.clampResistance(50))
        assertEquals(1500, BodyComposition.clampResistance(2000))
        assertEquals(500, BodyComposition.clampResistance(500))
    }

    @Test
    fun calcBmiMatchesBiasedTruncation() {
        assertClose(22.9, BodyComposition.calcBmi(175.0, 70.0))
        assertClose(24.7, BodyComposition.calcBmi(180.0, 80.0))
    }

    @Test
    fun calcBmiReturnsZeroForNonPositiveHeight() {
        assertEquals(0.0, BodyComposition.calcBmi(0.0, 70.0))
        assertEquals(0.0, BodyComposition.calcBmi(-10.0, 70.0))
    }

    @Test
    fun calcLbmFollowsIdentityAndZeroWithoutFat() {
        assertClose(63.0, BodyComposition.calcLbm(70.0, 10.0))
        assertEquals(0.0, BodyComposition.calcLbm(70.0, 0.0))
    }

    @Test
    fun limitBodyFatClampsAndRejectsMissingSignal() {
        assertEquals(5.1, BodyComposition.limitBodyFat(3.0, true))
        assertEquals(75.0, BodyComposition.limitBodyFat(80.0, true))
        assertEquals(20.0, BodyComposition.limitBodyFat(20.0, true))
        assertEquals(0.0, BodyComposition.limitBodyFat(20.0, false))
        assertEquals(0.0, BodyComposition.limitBodyFat(Double.NaN, true))
    }

    @Test
    fun impedanceValidCoversContactRange() {
        assertFalse(BodyComposition.isImpedanceValid(0))
        assertFalse(BodyComposition.isImpedanceValid(99))
        assertTrue(BodyComposition.isImpedanceValid(100))
        assertTrue(BodyComposition.isImpedanceValid(1500))
        assertTrue(BodyComposition.isImpedanceValid(60000))
        assertFalse(BodyComposition.isImpedanceValid(60001))
    }

    @Test
    fun sexFromCodeMapsOneToMale() {
        assertEquals(Sex.Male, Sex.fromCode(1))
        assertEquals(Sex.Female, Sex.fromCode(0))
        assertEquals(Sex.Female, Sex.fromCode(2))
    }

    @Test
    fun decodeWeightDividesAndRescales() {
        assertClose(70.0, BodyComposition.decodeWeight(700, 10.0))
        assertClose(70.0, BodyComposition.decodeWeight(7000, 10.0))
        assertEquals(0.0, BodyComposition.decodeWeight(0, 10.0))
    }

    @Test
    fun fourByteDecodeHonoursNoContactCutoff() {
        val fiveHundred = BodyComposition.fourResTwoByte2Int(0x01.toByte(), 0xF4.toByte())
        assertEquals(500, fiveHundred)
        assertEquals(0, BodyComposition.fourResTwoByte2Int(0xFF.toByte(), 0xFF.toByte()))
        assertEquals(0, BodyComposition.fourResTwoByte2Int(0xEA.toByte(), 0x60.toByte()))
        assertEquals(59999, BodyComposition.fourResTwoByte2Int(0xEA.toByte(), 0x5F.toByte()))
    }

    @Test
    fun eightByteDecodeScalesByTenth() {
        assertClose(50.0, BodyComposition.eightResTwoByte2Double(0x01.toByte(), 0xF4.toByte()))
        assertClose(0.0, BodyComposition.eightResTwoByte2Double(0x00.toByte(), 0x00.toByte()))
    }
}
