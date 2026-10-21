package com.vayunmathur.auto.platform

import com.vayunmathur.auto.protocol.VideoFocus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutoConnectionStateTest {
    @Test
    fun disconnectedIsASingleton() {
        assertEquals(AutoConnectionState.Disconnected, AutoConnectionState.Disconnected)
    }

    @Test
    fun connectingIsASingleton() {
        assertEquals(AutoConnectionState.Connecting, AutoConnectionState.Connecting)
    }

    @Test
    fun rejectedIsASingleton() {
        assertEquals(AutoConnectionState.Rejected, AutoConnectionState.Rejected)
    }

    @Test
    fun projectingDefaultsVideoFocusToNone() {
        val state = AutoConnectionState.Projecting("Test Car")
        assertEquals("Test Car", state.carName)
        assertEquals(VideoFocus.NONE, state.videoFocus)
    }

    @Test
    fun projectingCarriesVideoFocusSnapshot() {
        val state = AutoConnectionState.Projecting("Test Car", VideoFocus.PROJECTED)
        assertEquals(VideoFocus.PROJECTED, state.videoFocus)
    }

    @Test
    fun projectingCopyPreservesCarNameWhenFocusMoves() {
        val state = AutoConnectionState.Projecting("Test Car", VideoFocus.NONE)
        val moved = state.copy(videoFocus = VideoFocus.PROJECTED)
        assertEquals("Test Car", moved.carName)
        assertEquals(VideoFocus.PROJECTED, moved.videoFocus)
    }

    @Test
    fun statesAreExhaustiveAndDistinct() {
        val states: List<AutoConnectionState> = listOf(
            AutoConnectionState.Disconnected,
            AutoConnectionState.Connecting,
            AutoConnectionState.Projecting("Car"),
            AutoConnectionState.Rejected,
        )
        val labels = states.map { state ->
            when (state) {
                AutoConnectionState.Disconnected -> "disconnected"
                AutoConnectionState.Connecting -> "connecting"
                is AutoConnectionState.Projecting -> "projecting"
                AutoConnectionState.Rejected -> "rejected"
            }
        }
        assertEquals(listOf("disconnected", "connecting", "projecting", "rejected"), labels)
        assertTrue(states.toSet().size == 4, "each connection state must be distinct")
    }
}
