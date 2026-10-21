package com.vayunmathur.things.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScaleResistanceCodecTest {
    private fun mfgWithMarker(marker: Int): ByteArray {
        val mfg = ByteArray(16)
        mfg[MFG_CATEGORY_INDEX] = marker.toByte()
        return mfg
    }

    @Test
    fun swapBitExchangesDifferingBits() {
        assertEquals(32, swapBit(3, 5, 8))
        assertEquals(8, swapBit(3, 5, 32))
    }

    @Test
    fun swapBitKeepsValueWhenBitsMatch() {
        assertEquals(0, swapBit(0, 4, 0))
        assertEquals(0x11, swapBit(0, 4, 0x11))
        assertEquals(0xFF, swapBit(3, 5, 0xFF))
    }

    @Test
    fun resistanceCryptIsIdentityWithoutFlag() {
        assertEquals(255, resistanceCrypt(0xFF.toByte(), false))
        assertEquals(8, resistanceCrypt(0x08.toByte(), false))
    }

    @Test
    fun resistanceCryptSwapsBitPairsWithFlag() {
        assertEquals(32, resistanceCrypt(0x08.toByte(), true))
        assertEquals(8, resistanceCrypt(0x20.toByte(), true))
    }

    @Test
    fun resistanceCryptRoundTrips() {
        val encoded = resistanceCrypt(0x08.toByte(), true).toByte()
        assertEquals(8, resistanceCrypt(encoded, true))
    }

    @Test
    fun fourByteDecodeHonoursNoContactCutoff() {
        assertEquals(500, fourResTwoByte2Int(0x01.toByte(), 0xF4.toByte(), false))
        assertEquals(0, fourResTwoByte2Int(0xFF.toByte(), 0xFF.toByte(), false))
        assertEquals(0, fourResTwoByte2Int(0xEA.toByte(), 0x60.toByte(), false))
        assertEquals(59999, fourResTwoByte2Int(0xEA.toByte(), 0x5F.toByte(), false))
    }

    @Test
    fun fourByteDecodeDecryptsBeforeCutoff() {
        assertEquals(32, fourResTwoByte2Int(0x00.toByte(), 0x08.toByte(), true))
        assertEquals(8, fourResTwoByte2Int(0x00.toByte(), 0x08.toByte(), false))
    }

    @Test
    fun eightElectrodeDecodeScalesByTenth() {
        assertEquals(50.0, eightDouble(0x01.toByte(), 0xF4.toByte(), false))
        assertEquals(0.0, eightDouble(0x00.toByte(), 0x00.toByte(), false))
    }

    @Test
    fun scaleCategoryMapsMakerBytes() {
        assertEquals(CATEGORY_DEFAULT, qnScaleCategory(null))
        assertEquals(CATEGORY_DEFAULT, qnScaleCategory(ByteArray(0)))
        assertEquals(CATEGORY_DEFAULT, qnScaleCategory(ByteArray(11)))
        assertEquals(CATEGORY_101, qnScaleCategory(mfgWithMarker(MARKER_CAT_101)))
        assertEquals(CATEGORY_130, qnScaleCategory(mfgWithMarker(MARKER_CAT_130_A)))
        assertEquals(CATEGORY_130, qnScaleCategory(mfgWithMarker(MARKER_CAT_130_B)))
        assertEquals(CATEGORY_EIGHT_ELECTRODE, qnScaleCategory(mfgWithMarker(MARKER_CAT_EIGHT)))
        assertEquals(CATEGORY_134, qnScaleCategory(mfgWithMarker(MARKER_CAT_134_A)))
        assertEquals(CATEGORY_134, qnScaleCategory(mfgWithMarker(MARKER_CAT_134_B)))
        assertEquals(CATEGORY_128, qnScaleCategory(mfgWithMarker(MARKER_CAT_128_A)))
        assertEquals(CATEGORY_129, qnScaleCategory(mfgWithMarker(MARKER_CAT_129_B)))
        assertEquals(CATEGORY_135, qnScaleCategory(mfgWithMarker(MARKER_CAT_135)))
        assertEquals(CATEGORY_142, qnScaleCategory(mfgWithMarker(MARKER_CAT_142)))
        assertEquals(CATEGORY_143, qnScaleCategory(mfgWithMarker(MARKER_CAT_143)))
        assertEquals(CATEGORY_DEFAULT, qnScaleCategory(mfgWithMarker(0x00)))
    }

    @Test
    fun vaCategoriesHoldFourMembers() {
        assertTrue(CATEGORY_128 in VA_CATEGORIES)
        assertTrue(CATEGORY_129 in VA_CATEGORIES)
        assertTrue(CATEGORY_134 in VA_CATEGORIES)
        assertTrue(CATEGORY_143 in VA_CATEGORIES)
        assertFalse(CATEGORY_130 in VA_CATEGORIES)
    }

    @Test
    fun encryptFlagReadsBitTwoOnVaScales() {
        val flagged = ByteArray(16)
        flagged[MFG_ENCRYPT_FLAG_INDEX_VA] = 0x04.toByte()
        assertTrue(qnUsesResistanceEncrypt(CATEGORY_128, flagged))
        val clear = ByteArray(16)
        assertFalse(qnUsesResistanceEncrypt(CATEGORY_128, clear))
        assertFalse(qnUsesResistanceEncrypt(CATEGORY_128, null))
        assertFalse(qnUsesResistanceEncrypt(CATEGORY_128, ByteArray(10)))
    }

    @Test
    fun encryptFlagReadsBitZeroOnClassicScales() {
        val flagged = ByteArray(16)
        flagged[MFG_ENCRYPT_FLAG_INDEX_STD] = 0x01.toByte()
        assertTrue(qnUsesResistanceEncrypt(CATEGORY_DEFAULT, flagged))
        assertTrue(qnUsesResistanceEncrypt(CATEGORY_EIGHT_ELECTRODE, flagged))
        val clear = ByteArray(16)
        assertFalse(qnUsesResistanceEncrypt(CATEGORY_130, clear))
        assertFalse(qnUsesResistanceEncrypt(CATEGORY_101, flagged))
    }
}
