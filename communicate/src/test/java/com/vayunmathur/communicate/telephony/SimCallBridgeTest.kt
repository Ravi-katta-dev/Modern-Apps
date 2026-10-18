package com.vayunmathur.communicate.telephony

import android.telecom.Call
import com.vayunmathur.communicate.data.call.InAppCallPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimCallBridgeTest {
    @Test
    fun `ringing maps to incoming`() {
        assertEquals(InAppCallPhase.Incoming, SimCallBridge.mapPhase(Call.STATE_RINGING))
    }

    @Test
    fun `dialing connecting and account selection map to outgoing`() {
        assertEquals(InAppCallPhase.Outgoing, SimCallBridge.mapPhase(Call.STATE_DIALING))
        assertEquals(InAppCallPhase.Outgoing, SimCallBridge.mapPhase(Call.STATE_CONNECTING))
        assertEquals(InAppCallPhase.Outgoing, SimCallBridge.mapPhase(Call.STATE_SELECT_PHONE_ACCOUNT))
    }

    @Test
    fun `active and holding map to active`() {
        assertEquals(InAppCallPhase.Active, SimCallBridge.mapPhase(Call.STATE_ACTIVE))
        assertEquals(InAppCallPhase.Active, SimCallBridge.mapPhase(Call.STATE_HOLDING))
    }

    @Test
    fun `disconnecting and disconnected map to ended`() {
        assertEquals(InAppCallPhase.Ended, SimCallBridge.mapPhase(Call.STATE_DISCONNECTING))
        assertEquals(InAppCallPhase.Ended, SimCallBridge.mapPhase(Call.STATE_DISCONNECTED))
    }

    @Test
    fun `unknown states map to connecting rather than idle`() {
        assertEquals(InAppCallPhase.Connecting, SimCallBridge.mapPhase(Call.STATE_NEW))
    }

    @Test
    fun `sim capabilities offer keypad and hold but no video`() {
        val caps = SimCallBridge.capabilities
        assertTrue(caps.dtmf)
        assertTrue(caps.hold)
        assertTrue(!caps.video)
        assertTrue(!caps.screenShare)
    }
}
