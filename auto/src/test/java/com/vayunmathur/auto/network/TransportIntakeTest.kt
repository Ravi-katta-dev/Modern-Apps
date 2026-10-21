package com.vayunmathur.auto.network

import com.vayunmathur.auto.protocol.GalTransport
import com.vayunmathur.auto.protocol.TransportKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class TransportIntakeTest {
    private class FakeTransport : GalTransport {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
        override fun write(bytes: ByteArray, offset: Int, length: Int): Unit = Unit
        override fun flush(): Unit = Unit
        override fun close(): Unit = Unit
    }

    private fun drain() {
        while (TransportIntake.take() != null) {
            Unit
        }
    }

    private fun pending(carName: String, kind: TransportKind): TransportIntake.Pending {
        return TransportIntake.Pending(FakeTransport(), carName, kind)
    }

    @Test
    fun takeFromEmptyReturnsNull() {
        drain()
        assertNull(TransportIntake.take())
    }

    @Test
    fun offerThenTakeReturnsSameTransport() {
        drain()
        val transport = FakeTransport()
        val offered = TransportIntake.Pending(transport, "Test Car", TransportKind.USB)
        TransportIntake.offer(offered)
        val taken = assertNotNull(TransportIntake.take(), "expected a pending transport")
        assertSame(transport, taken.transport)
        assertEquals("Test Car", taken.carName)
        assertEquals(TransportKind.USB, taken.kind)
        assertNull(TransportIntake.take())
    }

    @Test
    fun queueIsFirstInFirstOut() {
        drain()
        TransportIntake.offer(pending("First", TransportKind.USB))
        TransportIntake.offer(pending("Second", TransportKind.WIRELESS))
        assertEquals("First", TransportIntake.take()?.carName)
        assertEquals("Second", TransportIntake.take()?.carName)
        assertNull(TransportIntake.take())
    }

    @Test
    fun transportKindsStayDistinctThroughIntake() {
        drain()
        TransportIntake.offer(pending("Usb", TransportKind.USB))
        TransportIntake.offer(pending("Wifi", TransportKind.WIRELESS))
        TransportIntake.offer(pending("Loop", TransportKind.TCP_LOOPBACK))
        assertEquals(TransportKind.USB, TransportIntake.take()?.kind)
        assertEquals(TransportKind.WIRELESS, TransportIntake.take()?.kind)
        assertEquals(TransportKind.TCP_LOOPBACK, TransportIntake.take()?.kind)
        assertNull(TransportIntake.take())
    }

    @Test
    fun dhuLoopbackPortMatchesHeadUnitDefault() {
        assertEquals(5277, HeadUnitServer.DHU_PORT)
    }
}
