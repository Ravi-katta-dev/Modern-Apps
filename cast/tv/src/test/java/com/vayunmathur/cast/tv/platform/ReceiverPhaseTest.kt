package com.vayunmathur.cast.tv.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The receiver's screen states, apart from any TV framework.
 *
 * Phases are plain data; the controller moves between them on sockets,
 * so the naming, defaults and source-name choice are pinned here.
 */
class ReceiverPhaseTest {

    @Test
    fun `pairing shows who is pairing and what to type`() {
        val phase = ReceiverPhase.Pairing(senderName = "Pixel 9", code = "123456", attemptsLeft = 3)
        assertEquals("Pixel 9", phase.senderName)
        assertEquals("123456", phase.code)
        assertEquals(3, phase.attemptsLeft)
    }

    @Test
    fun `pairing starts with the code unchanged`() {
        val phase = ReceiverPhase.Pairing(senderName = "Pixel 9", code = "123456", attemptsLeft = 3)
        assertFalse(phase.codeChanged)
    }

    @Test
    fun `pairing can flag that the code was replaced`() {
        val phase = ReceiverPhase.Pairing(
            senderName = "Pixel 9",
            code = "654321",
            attemptsLeft = 3,
            codeChanged = true,
        )
        assertTrue(phase.codeChanged)
    }

    @Test
    fun `mirroring names the app when one is casting`() {
        val phase = ReceiverPhase.Mirroring(
            senderName = "Pixel 9",
            width = 1920,
            height = 1080,
            appLabel = "YouPipe",
        )
        assertEquals("YouPipe", phase.sourceName)
    }

    @Test
    fun `mirroring falls back to the phone for a screen mirror`() {
        val phase = ReceiverPhase.Mirroring(senderName = "Pixel 9", width = 1080, height = 2400)
        assertEquals("Pixel 9", phase.sourceName)
    }

    @Test
    fun `mirroring treats blank app label as a screen mirror`() {
        val phase = ReceiverPhase.Mirroring(
            senderName = "Pixel 9",
            width = 1080,
            height = 2400,
            appLabel = "   ",
        )
        assertEquals("Pixel 9", phase.sourceName)
    }

    @Test
    fun `mirroring defaults to video with unknown rate`() {
        val phase = ReceiverPhase.Mirroring(senderName = "Pixel 9", width = 1920, height = 1080)
        assertTrue(phase.hasVideo)
        assertEquals(0f, phase.frameRate)
    }

    @Test
    fun `audio-only mirroring carries no picture`() {
        val phase = ReceiverPhase.Mirroring(
            senderName = "Pixel 9",
            width = 0,
            height = 0,
            hasVideo = false,
        )
        assertFalse(phase.hasVideo)
    }

    @Test
    fun `each failure names a different answer`() {
        val reasons = ReceiverFailure.values().map { ReceiverPhase.Failed(it).reason }
        assertEquals(ReceiverFailure.values().toList(), reasons)
    }

    @Test
    fun `connected keeps the sender name`() {
        assertEquals("Pixel 9", ReceiverPhase.Connected("Pixel 9").senderName)
    }
}
