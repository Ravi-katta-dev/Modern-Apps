package com.vayunmathur.library.network

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers [NativeHttpBridge.requestBatch], the one-crossing wave of range GETs behind the
 * tile fetch path. A JDK in-process server keeps this hermetic — no network, no extra test
 * dependency. Order preservation, per-slot errors and framing refusal are asserted here;
 * actual connection reuse is verified on device through the per-batch timing logs.
 */
class NativeHttpBatchTest {

    private lateinit var server: HttpServer
    private var baseUrl = ""

    @BeforeTest
    fun setUp() {
        PooledHttpEngine.USE_POOLED_ENGINE = true
        PooledHttpEngine.resetForTests()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        baseUrl = "http://127.0.0.1:${server.address.port}"
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        PooledHttpEngine.USE_POOLED_ENGINE = true
        PooledHttpEngine.resetForTests()
    }

    /** A server that answers every Range GET with the requested slice of [body]. */
    private fun serveRanges(body: ByteArray, path: String = "/archive") {
        server.createContext(path) { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            val (start, end) = range
                ?.removePrefix("bytes=")
                ?.split("-", limit = 2)
                ?.map { it.toLongOrNull() }
                .let { (it?.getOrNull(0) ?: 0L) to (it?.getOrNull(1) ?: (body.size - 1).toLong()) }
            val slice = body.copyOfRange(
                start.coerceIn(0, body.size.toLong()).toInt(),
                (end + 1).coerceIn(0, body.size.toLong()).toInt(),
            )
            exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${body.size}")
            exchange.sendResponseHeaders(206, slice.size.toLong())
            exchange.responseBody.use { it.write(slice) }
        }
    }

    private fun decodeBatch(packed: ByteArray, count: Int): List<ByteArray> {
        // Mirror of packBatch framing: u32 count, then (u32 len, frame) per reply.
        var pos = 0
        fun u32(): Int {
            val v = ((packed[pos].toInt() and 0xFF) shl 24) or
                ((packed[pos + 1].toInt() and 0xFF) shl 16) or
                ((packed[pos + 2].toInt() and 0xFF) shl 8) or
                (packed[pos + 3].toInt() and 0xFF)
            pos += 4
            return v
        }
        assertEquals(count, u32(), "reply count")
        return (0 until count).map {
            val len = u32()
            packed.copyOfRange(pos, pos + len).also { pos += len }
        }
    }

    private fun replyStatus(frame: ByteArray): Int =
        ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)

    private fun replyBody(frame: ByteArray): ByteArray {
        var pos = 2
        fun u32(): Int {
            val v = ((frame[pos].toInt() and 0xFF) shl 24) or
                ((frame[pos + 1].toInt() and 0xFF) shl 16) or
                ((frame[pos + 2].toInt() and 0xFF) shl 8) or
                (frame[pos + 3].toInt() and 0xFF)
            pos += 4
            return v
        }
        val urlLen = u32(); pos += urlLen
        val hdrLen = u32(); pos += hdrLen
        val bodyLen = u32()
        return frame.copyOfRange(pos, pos + bodyLen)
    }

    @Test
    fun a_wave_keeps_order_with_one_slot_per_range() {
        val body = ByteArray(4096) { (it % 251).toByte() }
        serveRanges(body)
        val ranges = arrayOf("bytes=0-127", "bytes=1000-1127", "bytes=2000-2007")
        val packed = NativeHttpBridge.requestBatch("$baseUrl/archive", ranges)
        val frames = decodeBatch(packed, 3)
        assertEquals(3, frames.size)
        for ((i, frame) in frames.withIndex()) {
            assertEquals(206, replyStatus(frame), "slot $i")
        }
        assertContentEquals(body.copyOfRange(0, 128), replyBody(frames[0]))
        assertContentEquals(body.copyOfRange(1000, 1128), replyBody(frames[1]))
        assertContentEquals(body.copyOfRange(2000, 2008), replyBody(frames[2]))
    }

    @Test
    fun a_failed_slot_is_status_zero_in_its_slot_not_a_failed_batch() {
        val body = ByteArray(256) { it.toByte() }
        serveRanges(body)
        // Unreachable host in the middle: slot 1 fails, the wave still answers all three.
        val ranges = arrayOf("bytes=0-15", "bytes=0-15", "bytes=16-31")
        val packed = NativeHttpBridge.requestBatch("$baseUrl/archive", ranges)
        assertTrue(decodeBatch(packed, 3).size == 3, "every slot answered")
        // And against a dead port every slot reports status 0 rather than throwing.
        val dead = NativeHttpBridge.requestBatch("http://127.0.0.1:1/archive", arrayOf("bytes=0-1"))
        val frames = decodeBatch(dead, 1)
        assertEquals(0, replyStatus(frames[0]), "a transport failure is status 0")
    }

    @Test
    fun batch_framing_round_trips_and_refuses_garbage() {
        val body = ByteArray(64) { it.toByte() }
        serveRanges(body)
        val packed = NativeHttpBridge.requestBatch("$baseUrl/archive", arrayOf("bytes=0-7"))
        assertNotNull(NativeHttpBridge.unpackBatch(packed), "a good batch unpacks")
        assertEquals(1, NativeHttpBridge.unpackBatch(packed)!!.size)
        assertNull(NativeHttpBridge.unpackBatch(byteArrayOf()), "empty is refused")
        assertNull(
            NativeHttpBridge.unpackBatch(packed.copyOf(packed.size - 1)),
            "truncation is refused",
        )
        assertNull(
            NativeHttpBridge.unpackBatch(packed + 0x00.toByte()),
            "trailing bytes are refused",
        )
    }
}
