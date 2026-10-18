package com.vayunmathur.communicate.telephony

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.VideoProfile
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.call.CallCapabilities
import com.vayunmathur.communicate.data.call.InAppCallController
import com.vayunmathur.communicate.data.call.InAppCallDtmfController
import com.vayunmathur.communicate.data.call.InAppCallHoldController
import com.vayunmathur.communicate.data.call.InAppCallPhase
import com.vayunmathur.communicate.data.call.InAppCallRegistry
import com.vayunmathur.library.log.Log

/**
 * Mirrors the carrier/SIM [Call] owned by [CommunicateInCallService] into the shared [InAppCallRegistry]
 * so regular phone calls get the same call screen as every other line — including the dial pad the issue
 * calls out (voicemail and phone menus need DTMF).
 *
 * An observer rather than a change inside the service: the service keeps owning the system call surface
 * and notifications, and this only projects the state it already publishes. Same pattern as
 * [com.vayunmathur.communicate.data.googlevoice.call.GoogleVoiceCallBridge].
 */
object SimCallBridge {
    private const val TAG = "SimCallBridge"

    /** The capabilities a regular phone call gets on the shared screen: keypad and hold. */
    val capabilities = CallCapabilities(dtmf = true, hold = true)

    /**
     * The carrier call the registry is mirroring. A second leg (call waiting) never replaces it: its
     * state changes are ignored and its removal ends nothing, so declining call-waiting cannot dismiss
     * the screen of the call still up.
     */
    @Volatile
    private var mirroredCall: Call? = null

    /**
     * Publish a carrier call that just appeared. [peerId]/[peerName] are the number and the resolved
     * contact name. Idempotent per call — the service calls it once from `onCallAdded`.
     */
    fun onCallAdded(service: CommunicateInCallService, call: Call, peerId: String, peerName: String) {
        val current = InAppCallRegistry.state.value
        if (current.phase != InAppCallPhase.Idle && current.phase != InAppCallPhase.Ended) {
            // The screen is already driving a live call: a second SIM leg (call waiting) shares nothing,
            // and a SIM call arriving during a VoIP call stays notification-only rather than clobbering
            // the other line's state.
            return
        }
        val telecomState = call.details.state
        val incoming = telecomState == Call.STATE_RINGING
        Log.status(TAG, "SIM call starting incoming=$incoming peer=$peerId")
        InAppCallRegistry.bind(CommunicateLine.Sim, simController(service, call), capabilities)
        InAppCallRegistry.dtmfController = simDtmf(call)
        InAppCallRegistry.holdController = simHold(call)
        InAppCallRegistry.onCallStarting(
            line = CommunicateLine.Sim,
            peerId = peerId,
            peerName = peerName.ifBlank { peerId },
            isVideo = false,
            incoming = incoming,
            capabilities = capabilities,
        )
        InAppCallRegistry.onPhase(mapPhase(telecomState))
        if (telecomState == Call.STATE_HOLDING) InAppCallRegistry.onHeld(true)
        mirroredCall = call
    }

    /**
     * Reflect a `Call` state change. Anything that is not the mirrored leg — call waiting, a second SIM
     * leg after a swap — is ignored.
     *
     * [telecomState] is the state the callback already reports, so the deprecated `Call.getState()`
     * is never read.
     */
    fun onStateChanged(call: Call, telecomState: Int) {
        if (call != mirroredCall) return
        if (InAppCallRegistry.state.value.line != CommunicateLine.Sim) return
        InAppCallRegistry.onPhase(mapPhase(telecomState))
        if (telecomState == Call.STATE_HOLDING || telecomState == Call.STATE_ACTIVE) {
            InAppCallRegistry.onHeld(telecomState == Call.STATE_HOLDING)
        }
        if (telecomState == Call.STATE_DISCONNECTED) {
            InAppCallRegistry.onHeld(false)
        }
    }

    /** Reflect the system mute / audio-route change onto the shared state. */
    fun onAudioStateChanged(audioState: CallAudioState) {
        if (InAppCallRegistry.state.value.line != CommunicateLine.Sim) return
        InAppCallRegistry.onMuted(audioState.isMuted)
        InAppCallRegistry.onSpeaker(audioState.route == CallAudioState.ROUTE_SPEAKER)
    }

    /** Ends the mirror when the mirrored carrier call goes away; other legs end nothing. */
    fun onCallRemoved(call: Call) {
        if (call != mirroredCall) return
        mirroredCall = null
        val current = InAppCallRegistry.state.value
        if (current.line == CommunicateLine.Sim && current.phase != InAppCallPhase.Idle) {
            InAppCallRegistry.onEnded(null)
        }
    }

    fun mapPhase(telecomState: Int): InAppCallPhase = when (telecomState) {
        Call.STATE_RINGING -> InAppCallPhase.Incoming
        Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT -> InAppCallPhase.Outgoing
        Call.STATE_ACTIVE, Call.STATE_HOLDING -> InAppCallPhase.Active
        Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> InAppCallPhase.Ended
        // New / incoming-pending / audio-processing: the call exists but has no direction yet.
        else -> InAppCallPhase.Connecting
    }

    private fun simController(service: CommunicateInCallService, call: Call) = object : InAppCallController {
        override fun answer() {
            call.answer(VideoProfile.STATE_AUDIO_ONLY)
        }

        override fun reject() {
            call.reject(false, null)
        }

        override fun hangup() {
            call.disconnect()
        }

        override fun setMuted(muted: Boolean) {
            service.setMuted(muted)
        }

        override fun setSpeaker(on: Boolean) {
            @Suppress("DEPRECATION")
            service.setAudioRoute(
                if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE,
            )
        }
    }

    private fun simDtmf(call: Call) = object : InAppCallDtmfController {
        override fun sendDtmf(digit: String) {
            call.playDtmfTone(digit.firstOrNull() ?: return)
        }
    }

    private fun simHold(call: Call) = object : InAppCallHoldController {
        override fun setHeld(held: Boolean) {
            if (held) call.hold() else call.unhold()
        }
    }
}
