package com.vayunmathur.sdk.controller

import com.vayunmathur.e2ee.E2ee
import java.io.DataInput
import java.io.EOFException

/**
 * `int32be(len) ‖ payload` on a TCP stream.
 *
 * The same idiom `:cast:protocol` uses, kept because it was written and tested. A frame larger
 * than [MAX_FRAME_BYTES] is refused rather than allocated: an input sample is tens of bytes, so
 * this is orders of magnitude of headroom, and the point is only that a corrupt or hostile length
 * prefix cannot ask for a gigabyte.
 */
object ControllerFraming {

    private const val LENGTH_PREFIX_BYTES = 4
    private const val LAST_PREFIX_INDEX = 3
    private const val BYTE_SHIFT_HIGH = 24
    private const val BYTE_SHIFT_MID_HIGH = 16
    private const val BYTE_SHIFT_MID_LOW = 8

    const val MAX_FRAME_BYTES = 1 shl 20

    fun encode(body: ByteArray): ByteArray {
        val out = ByteArray(LENGTH_PREFIX_BYTES + body.size)
        out[0] = (body.size ushr BYTE_SHIFT_HIGH).toByte()
        out[1] = (body.size ushr BYTE_SHIFT_MID_HIGH).toByte()
        out[2] = (body.size ushr BYTE_SHIFT_MID_LOW).toByte()
        out[LAST_PREFIX_INDEX] = body.size.toByte()
        body.copyInto(out, LENGTH_PREFIX_BYTES)
        return out
    }

    /**
     * One frame's body, or null at a clean end of stream.
     *
     * Throws on a length prefix outside the bound: the stream cannot be resynchronised after one, so
     * carrying on would read a payload as a length forever.
     */
    fun read(input: DataInput): ByteArray? {
        val length = try {
            input.readInt()
        } catch (_: EOFException) {
            return null
        }
        require(length in 1..MAX_FRAME_BYTES) { "refusing a $length byte controller frame" }
        return ByteArray(length).also { input.readFully(it) }
    }
}

/**
 * Turns [ControllerMessage]s into frame bodies and back, encrypting once the session key is known.
 *
 * The cipher is installed by [useSessionKey] at exactly one point in the sequence - immediately
 * after `SEALED_SECRET` - on both ends. That is what keeps them in step: there is no per-frame flag
 * saying whether a body is encrypted, because a flag an attacker can clear is a downgrade.
 *
 * AES-256-GCM via `E2ee.aesEncrypt`, so the channel *is* authenticated. A tampered frame fails its
 * tag and closes the session.
 */
class ControllerCodec {

    private var sessionKey: ByteArray? = null

    /** True once [useSessionKey] has been called, i.e. from `PAIR_REQUIRED` onward. */
    val isEncrypting: Boolean get() = sessionKey != null

    fun useSessionKey(key: ByteArray) {
        sessionKey = key
    }

    /** The frame body for [message]: JSON, then encrypted if the key is installed. */
    fun encode(message: ControllerMessage): ByteArray {
        val json = ControllerJson.encodeToString(message).toByteArray(Charsets.UTF_8)
        val key = sessionKey ?: return json
        return E2ee.aesEncrypt(key, json)
    }

    /**
     * The message in [body], or null when it is not one.
     *
     * Null covers a failed GCM tag, a payload that is not JSON, and a `type` this build does not
     * know. All three are handled the same way by both ends - close the session - because after the
     * cipher is installed there is no benign reason for any of them.
     */
    fun decode(body: ByteArray): ControllerMessage? {
        val key = sessionKey
        val json = if (key == null) {
            body
        } else {
            runCatching { E2ee.aesDecrypt(key, body) }.getOrNull() ?: return null
        }
        return runCatching {
            ControllerJson.decodeFromString<ControllerMessage>(json.toString(Charsets.UTF_8))
        }.getOrNull()
    }
}
