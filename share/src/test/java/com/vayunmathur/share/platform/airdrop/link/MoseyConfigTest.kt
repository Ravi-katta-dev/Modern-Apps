package com.vayunmathur.share.platform.airdrop.link

import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoseyConfigTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `transfer updates carry the kind and the raw peer address`() {
        val peer = InetAddress.getByName("fe80::1") as Inet6Address
        val expected = bytes(0x10, 0x02, 0x1A, 0x10) + peer.address
        assertArrayEquals(expected, MoseyConfig.transferStarted(peer))
        assertEquals(0x03, MoseyConfig.transferEnded(peer)[1].toInt())
    }

    @Test
    fun `sta update encodes channel then kind`() {
        assertArrayEquals(bytes(0x08, 0x95, 0x01, 0x10, 0x01), MoseyConfig.staChannelUpdate(149))
        assertArrayEquals(bytes(0x10, 0x01), MoseyConfig.staChannelUpdate(null))
    }

    @Test
    fun `start config uses 80 MHz on the default channels and 20 MHz on the fallback`() {
        val default = MoseyConfig.start(MoseyConfig.DEFAULT_CHANNELS, staChannel = null, dualBandSimultaneous = false)
        assertTrue(default.containsSequence(bytes(0x48, 0x02)))
        val fallback = MoseyConfig.start(MoseyConfig.FALLBACK_CHANNELS, staChannel = null, dualBandSimultaneous = false)
        assertFalse(fallback.containsSequence(bytes(0x48)))
        // Six packed DATA power-profile slots.
        assertTrue(default.containsSequence(bytes(0x42, 0x06, 0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun `start config marks dual band and sta channel first`() {
        val cfg = MoseyConfig.start(MoseyConfig.DEFAULT_CHANNELS, staChannel = 36, dualBandSimultaneous = true)
        assertArrayEquals(bytes(0x08, 0x01, 0x10, 0x24), cfg.copyOf(4))
    }

    @Test
    fun `capability parses the channel hopping bit`() {
        assertTrue(MoseyConfig.isChannelHoppingSupported(bytes(0x08, 0x01)))
        assertFalse(MoseyConfig.isChannelHoppingSupported(ByteArray(0)))
        assertFalse(MoseyConfig.isChannelHoppingSupported(null))
        assertTrue(MoseyConfig.isChannelHoppingSupported(bytes(0x12, 0x01, 0x00, 0x08, 0x01)))
    }

    private fun ByteArray.containsSequence(seq: ByteArray): Boolean =
        (0..size - seq.size).any { i -> seq.indices.all { this[i + it] == seq[it] } }
}
