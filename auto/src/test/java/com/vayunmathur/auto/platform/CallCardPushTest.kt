package com.vayunmathur.auto.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallCardPushTest {
    private fun ringing(number: String?): ActiveCallInfo {
        return ActiveCallInfo(telecomState = 1, number = number, acceptedMs = 0L)
    }

    @Test
    fun pushWithNoSessionInstalledIsANoOp() {
        CallCardPush.push = null
        CallCardPush.push(ringing("5550100"))
    }

    @Test
    fun installedPushForwardsSnapshot() {
        var seen: ActiveCallInfo? = null
        var calls = 0
        CallCardPush.push = { info ->
            seen = info
            calls++
        }
        try {
            val info = ringing("5550100")
            CallCardPush.push(info)
            assertEquals(1, calls)
            assertEquals(info, seen)
        } finally {
            CallCardPush.push = null
        }
    }

    @Test
    fun installedPushForwardsNullOnRemove() {
        var seen: ActiveCallInfo? = ringing("5550100")
        CallCardPush.push = { info -> seen = info }
        try {
            CallCardPush.push(null)
            assertNull(seen)
        } finally {
            CallCardPush.push = null
        }
    }

    @Test
    fun clearedPushStopsForwarding() {
        var calls = 0
        CallCardPush.push = { calls++ }
        CallCardPush.push = null
        CallCardPush.push(ringing("5550100"))
        assertEquals(0, calls)
    }

    @Test
    fun throwingPushNeverEscapesIntoTelecomCallbacks() {
        CallCardPush.push = { throw IllegalStateException("torn down") }
        try {
            CallCardPush.push(ringing("5550100"))
            CallCardPush.push(null)
        } finally {
            CallCardPush.push = null
        }
    }

    @Test
    fun latestSnapshotWinsAcrossUpdates() {
        val forwarded = mutableListOf<ActiveCallInfo?>()
        CallCardPush.push = { info -> forwarded.add(info) }
        try {
            CallCardPush.push(ringing("5550100"))
            CallCardPush.push(ringing("5550100").copy(acceptedMs = 42L, held = true))
            assertEquals(2, forwarded.size)
            assertTrue(forwarded.last()!!.held)
        } finally {
            CallCardPush.push = null
        }
    }
}
