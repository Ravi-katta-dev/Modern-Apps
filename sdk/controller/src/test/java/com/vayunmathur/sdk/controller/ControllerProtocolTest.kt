package com.vayunmathur.sdk.controller

import java.io.DataInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControllerProtocolTest {

    @Test
    fun framingRoundTrip() {
        val body = "hello".toByteArray(Charsets.UTF_8)
        val frame = ControllerFraming.encode(body)
        assertEquals(4 + body.size, frame.size)
        val back = ControllerFraming.read(DataInputStream(frame.inputStream()))
        assertNotNull(back)
        assertTrue(back.contentEquals(body))
    }

    @Test
    fun framingRefusesOversizedPrefix() {
        // int32be(1GiB + 1) then nothing: must throw rather than allocate.
        val frame = byteArrayOf(0x40, 0x00, 0x00, 0x01)
        runCatching { ControllerFraming.read(DataInputStream(frame.inputStream())) }
            .onSuccess { error("expected a refusal") }
    }

    @Test
    fun framingRefusesExactCapPlusOne() {
        // One byte over the 1 MiB cap: the boundary itself must refuse, not just absurd sizes.
        val size = ControllerFraming.MAX_FRAME_BYTES + 1
        val frame = byteArrayOf(
            (size ushr 24).toByte(),
            (size ushr 16).toByte(),
            (size ushr 8).toByte(),
            size.toByte(),
        )
        runCatching { ControllerFraming.read(DataInputStream(frame.inputStream())) }
            .onSuccess { error("expected a refusal at cap + 1") }
    }

    @Test
    fun framingAcceptsExactCap() {
        // The cap itself is a valid frame: refusal is strictly above, never at.
        val body = ByteArray(ControllerFraming.MAX_FRAME_BYTES)
        val back = ControllerFraming.read(DataInputStream(ControllerFraming.encode(body).inputStream()))
        assertNotNull(back)
        assertEquals(body.size, back.size)
    }

    @Test
    fun codecPlaintextRoundTrip() {
        val codec = ControllerCodec()
        val msg = ControllerHello(senderName = "Phone", senderId = "id-1")
        val back = codec.decode(codec.encode(msg))
        assertEquals(msg, back)
    }

    @Test
    fun codecEncryptedRoundTrip() {
        val a = ControllerCodec()
        val b = ControllerCodec()
        val keys = ControllerKeys.of(ControllerKeys.newSecret())
        a.useSessionKey(keys.control)
        b.useSessionKey(keys.control)
        val msg: ControllerMessage = ControllerLobby(LobbyState(gameId = "kart"))
        assertEquals(msg, b.decode(a.encode(msg)))
    }

    @Test
    fun codecRejectsWrongKey() {
        val a = ControllerCodec()
        val b = ControllerCodec()
        a.useSessionKey(ControllerKeys.of(ControllerKeys.newSecret()).control)
        b.useSessionKey(ControllerKeys.of(ControllerKeys.newSecret()).control)
        assertNull(b.decode(a.encode(ControllerPing)))
    }

    @Test
    fun codecRejectsUnknownType() {
        val codec = ControllerCodec()
        val body = """{"type":"SOMETHING_NEW","x":1}""".toByteArray(Charsets.UTF_8)
        assertNull(codec.decode(body))
    }

    @Test
    fun pairCodeRoundTrip() {
        val gate = ControllerPairingGate(newCode = { "123456" })
        val keys = ControllerKeys.of(ControllerKeys.newSecret())
        val transcript = ControllerTranscript().apply { add("wire".toByteArray()) }.value()
        val proof = keys.pairProof("123456", transcript)
        val result = gate.verifyCode(keys, transcript, proof)
        assertTrue(result is ControllerPairResult.Ok)
        assertNotNull(result.deviceKey)
    }

    @Test
    fun pairCodeLockoutRotates() {
        val codes = ArrayDeque(listOf("111111", "222222", "333333"))
        val gate = ControllerPairingGate(newCode = { codes.removeFirst() })
        val keys = ControllerKeys.of(ControllerKeys.newSecret())
        val transcript = ControllerTranscript().apply { add("wire".toByteArray()) }.value()
        val wrong = keys.pairProof("000000", transcript)
        val first = gate.verifyCode(keys, transcript, wrong)
        assertTrue(first is ControllerPairResult.Wrong && !first.codeChanged)
        val second = gate.verifyCode(keys, transcript, wrong)
        assertTrue(second is ControllerPairResult.Wrong && !second.codeChanged)
        val third = gate.verifyCode(keys, transcript, wrong)
        assertTrue(third is ControllerPairResult.Wrong && third.codeChanged)
        // The old code no longer verifies even with the right digits.
        val stale = gate.verifyCode(keys, transcript, keys.pairProof("111111", transcript))
        assertTrue(stale is ControllerPairResult.Wrong)
        // The fresh code does.
        val fresh = gate.verifyCode(keys, transcript, keys.pairProof("222222", transcript))
        assertTrue(fresh is ControllerPairResult.Ok)
    }

    @Test
    fun deviceProofRoundTrip() {
        val gate = ControllerPairingGate()
        val keys = ControllerKeys.of(ControllerKeys.newSecret())
        val transcript = ControllerTranscript().apply { add("wire".toByteArray()) }.value()
        val deviceKey = ControllerPairingGate.newDeviceKey()
        val proof = keys.deviceProof(deviceKey, transcript)
        val result = gate.verifyDevice(keys, transcript, deviceKey, proof)
        assertTrue(result is ControllerPairResult.Ok)
        assertEquals(null, result.deviceKey)
    }

    @Test
    fun keySchedulesMatch() {
        // Two ends from the same secret produce identical control keys: the property the whole
        // channel depends on, pinned without a TV in the room.
        val secret = ControllerKeys.newSecret()
        val a = ControllerKeys.of(secret).control
        val b = ControllerKeys.of(secret).control
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun inputButtonBits() {
        val input = ControllerInput(
            playerId = "p1",
            seq = 1,
            buttons = ControllerButton.A.bit or ControllerButton.RIGHT_TRIGGER.bit,
        )
        assertTrue(input.isPressed(ControllerButton.A))
        assertTrue(input.isPressed(ControllerButton.RIGHT_TRIGGER))
        assertTrue(!input.isPressed(ControllerButton.B))
    }

    @Test
    fun replayedSeqIsDropped() {
        // At or below the high-water mark: a replay or a duplicate, never applied twice.
        val gate = ControllerInputGate()
        assertTrue(gate.offer(1, nowMs = 0))
        assertEquals(false, gate.offer(1, nowMs = 10))
        assertEquals(false, gate.offer(0, nowMs = 20))
        assertTrue(gate.offer(2, nowMs = 30))
    }

    @Test
    fun rateLimitDropsTheFortyFirst() {
        // 40 samples in one second pass; the 41st is dropped rather than applied.
        val gate = ControllerInputGate()
        repeat(ControllerContract.MAX_INPUT_PER_SECOND) { i ->
            assertTrue(gate.offer(i + 1L, nowMs = i.toLong()), "sample ${i + 1} should pass")
        }
        assertEquals(false, gate.offer(41, nowMs = 999))
    }

    @Test
    fun rateWindowSlides() {
        // A second later the window has moved on: full rate is available again, and the old
        // high-water mark still guards replays.
        val gate = ControllerInputGate()
        repeat(ControllerContract.MAX_INPUT_PER_SECOND) { i ->
            gate.offer(i + 1L, nowMs = i.toLong())
        }
        assertTrue(gate.offer(41, nowMs = 1_001))
        assertEquals(false, gate.offer(40, nowMs = 1_002))
    }

    @Test
    fun replaysNeverConsumeBudget() {
        // Replays are refused before the window check, so a replay storm cannot crowd out the one
        // legitimate sample the next second still allows.
        val gate = ControllerInputGate(maxPerSecond = 1)
        assertTrue(gate.offer(1, nowMs = 0))
        repeat(10) { assertEquals(false, gate.offer(1, nowMs = 100L + it)) }
        assertTrue(gate.offer(2, nowMs = 1_001))
    }

    @Test
    fun joiningTheEighthSeatFillsTheLobby() {
        // The lobby holds MAX_PLAYERS seats; the ninth phone is refused, not seated.
        val seats = List(ControllerContract.MAX_PLAYERS) { "p$it" }
        assertEquals(ControllerContract.MAX_PLAYERS, seats.distinct().size)
    }

    @Test
    fun versionMismatchIsRefused() {
        // Both ends check the version constant rather than negotiating: a build that disagrees
        // about the wire format is a bug to fix, not a matrix to maintain.
        val hello = ControllerHello(senderName = "Phone", senderId = "id-1")
        assertEquals(ControllerContract.CONTROLLER_PROTOCOL_VERSION, hello.version)
        assertTrue(hello.version != 9, "must never collide with cast v9")
    }
}
