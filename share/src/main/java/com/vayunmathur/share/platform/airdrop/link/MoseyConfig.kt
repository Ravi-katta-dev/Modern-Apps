package com.vayunmathur.share.platform.airdrop.link

import java.io.ByteArrayOutputStream
import java.net.Inet6Address

/**
 * The opaque config blobs the Mosey HAL takes, hand-encoded as the protobuf-lite messages
 * Mosey builds (`btk` for `start`, `btn` for `update`, `btc` for the capability reply).
 *
 * Field numbers and the defaults below are recovered from Mosey's schema strings and its
 * Phenotype fallbacks (`dlr`). Live Phenotype values on a Pixel can differ; these are what a
 * Mosey build with no server config sends.
 */
object MoseyConfig {

    /** Mosey's default AWDL channels when nothing rules them out (`byt`). */
    val DEFAULT_CHANNELS = intArrayOf(149, 44)

    /** Its 2.4 GHz fallback when no 5 GHz channel is usable. */
    val FALLBACK_CHANNELS = intArrayOf(6)

    private const val BANDWIDTH_20MHZ = 0
    private const val BANDWIDTH_80MHZ = 2
    private const val BANDWIDTH_INVALID = -1

    /** `btm` constants as they land on the wire (`btm.a(i) = i - 2`). */
    private const val UPDATE_STA_CHANNEL = 1
    private const val UPDATE_TRANSFER_STARTED = 2
    private const val UPDATE_TRANSFER_ENDED = 3

    /** Six `btj` "DATA" entries, one per Phenotype-selected power profile slot. */
    private const val POWER_PROFILE_SLOTS = 6

    /**
     * `startMoseyConfig` (`btk`, built in `byt.m`).
     *
     * [staChannel] is the infrastructure channel the phone is associated on, or null when not
     * connected; [dualBandSimultaneous] is `WifiManager.isDualBandSimultaneousSupported()`.
     */
    fun start(channels: IntArray, staChannel: Int?, dualBandSimultaneous: Boolean): ByteArray = Proto().apply {
        bool(1, dualBandSimultaneous)
        int32(2, staChannel ?: 0)
        // 3..6: Phenotype booleans x(), y(), w() (channel hopping), B(), all false by default.
        // 7: bti enum from c() = 0, which maps to wire 0 and is omitted.
        packedEnums(8, IntArray(POWER_PROFILE_SLOTS))
        int32(9, bandwidthFor(channels))
        int32(10, 10) // e()
        int32(11, 10) // d()
        int32(12, 10) // a()
        int32(13, 2) // o()
        int32(14, 4) // g()
        // 15: STA links (repeated bsp); left empty, `d` already carries the STA channel.
        int32(16, 10) // f()
        // 17: A() false
        int32(18, 8192) // k()
        int32(19, 8192) // h()
        int32(20, 16) // m()
        int32(21, 32) // j()
        int32(22, 32) // l()
        int32(23, 64) // i()
        int32(24, 16_777_216) // n()
        bool(25, true) // z()
        // 26: v() false; 27: bth enum from b() = 0, omitted.
    }.bytes()

    /** `updateMoseyConfig` telling the daemon a transfer to [peer] began. */
    fun transferStarted(peer: Inet6Address): ByteArray = transfer(UPDATE_TRANSFER_STARTED, peer)

    fun transferEnded(peer: Inet6Address): ByteArray = transfer(UPDATE_TRANSFER_ENDED, peer)

    /** `updateMoseyConfig` reporting the infrastructure channel moved (or was lost). */
    fun staChannelUpdate(staChannel: Int?): ByteArray = Proto().apply {
        int32(1, staChannel ?: 0)
        int32(2, UPDATE_STA_CHANNEL)
    }.bytes()

    private fun transfer(kind: Int, peer: Inet6Address): ByteArray = Proto().apply {
        int32(2, kind)
        bytes(3, peer.address)
    }.bytes()

    /** `btc.b`: whether the radio can hop between the AWDL and infrastructure channels. */
    fun isChannelHoppingSupported(capability: ByteArray?): Boolean {
        if (capability == null) return false
        var i = 0
        while (i < capability.size) {
            val (key, next) = readVarint(capability, i)
            i = next
            when ((key and 7L).toInt()) {
                0 -> {
                    val (value, after) = readVarint(capability, i)
                    if (key ushr 3 == 1L) return value != 0L
                    i = after
                }
                2 -> {
                    val (len, after) = readVarint(capability, i)
                    i = after + len.toInt()
                }
                else -> return false
            }
        }
        return false
    }

    private fun bandwidthFor(channels: IntArray): Int = when {
        channels.contentEquals(FALLBACK_CHANNELS) -> BANDWIDTH_20MHZ
        6 in channels || (149 !in channels && 44 !in channels) -> BANDWIDTH_INVALID
        else -> BANDWIDTH_80MHZ
    }

    private fun readVarint(b: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = start
        while (i < b.size) {
            val byte = b[i++].toInt()
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return result to i
            shift += 7
        }
        return result to i
    }

    /** Minimal proto3 writer: zero/false/empty values are omitted, as protobuf-lite does. */
    private class Proto {
        private val out = ByteArrayOutputStream()

        fun bool(field: Int, value: Boolean) {
            if (value) int32(field, 1)
        }

        fun int32(field: Int, value: Int) {
            if (value == 0) return
            varint((field shl 3).toLong())
            // Negative int32/enum values are sign-extended to ten bytes on the wire.
            varint(value.toLong())
        }

        fun bytes(field: Int, value: ByteArray) {
            if (value.isEmpty()) return
            varint(((field shl 3) or 2).toLong())
            varint(value.size.toLong())
            out.write(value)
        }

        fun packedEnums(field: Int, values: IntArray) {
            if (values.isEmpty()) return
            val body = Proto().apply { values.forEach { varint(it.toLong()) } }.bytes()
            bytes(field, body)
        }

        fun varint(value: Long) {
            var v = value
            while (v and 0x7FL.inv() != 0L) {
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
        }

        fun bytes(): ByteArray = out.toByteArray()
    }
}
