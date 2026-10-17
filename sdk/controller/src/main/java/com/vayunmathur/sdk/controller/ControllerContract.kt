package com.vayunmathur.sdk.controller

/**
 * The wire contract for the generic phone-as-controller protocol.
 *
 * Kart-agnostic by design: buttons are a bitmask, sticks are normalized axes, and nothing here
 * names a kart, a track, or an item. A future TV game reuses this SDK by assigning its own
 * meaning to the same buttons and axes.
 *
 * Transport: TCP, JSON frames, 20-30 Hz input cadence. UDP later is a transport swap: [seq] and
 * [clientTimestampMs]/[serverTimestampMs] plus idempotent apply are reserved from v1 so the
 * sequence/snapshot discipline does not have to be reinvented.
 */
object ControllerContract {

    /**
     * The protocol version in every [ControllerHello] and [ControllerHostHello].
     *
     * Checked on both sides and refused on mismatch, rather than negotiated down. Two of our own
     * builds that disagree about the wire format are a bug to fix, not a compatibility matrix to
     * maintain - both halves ship from this repo at the same version.
     */
    const val CONTROLLER_PROTOCOL_VERSION = 1

    /** The mDNS service type the TV registers and phones browse for. `_macast._tcp` is untouched. */
    const val GAMEPAD_SERVICE_TYPE = "_gamepad._tcp"

    /**
     * How often a connected phone emits input while it has anything to say.
     *
     * 30 Hz keeps a 60 Hz tick fed every other frame; the host interpolates between samples rather
     * than stepping.
     */
    const val INPUT_HZ = 30

    /**
     * Per-player inbound rate limit, in messages per second.
     *
     * A well-behaved phone sends [INPUT_HZ]; 40 leaves headroom for jitter without letting a
     * faulty or hostile client flood the tick. Over the limit, the host drops rather than applies.
     */
    const val MAX_INPUT_PER_SECOND = 40

    /**
     * How many player slots the host offers.
     *
     * Four plays a full Kart grid with room for AI; eight is the ceiling before the lobby UI and
     * the tick budget both get silly.
     */
    const val MAX_PLAYERS = 8

    /**
     * How often each end says PING while a session is open.
     *
     * A read timeout is the only liveness check either end has, and it cannot tell a quiet lobby
     * from a dead one. Both ends echo PING, so a session with no input and no lobby change still
     * produces traffic well inside the 60 s read bound - even while a human is reading six digits.
     */
    const val PING_INTERVAL_MS = 20_000L
}
