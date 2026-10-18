package com.vayunmathur.desktop.notes.systemsinks.linux

import com.vayunmathur.desktop.notes.systemsinks.FakeRelayTransport
import com.vayunmathur.everysync.core.data.RemoteTodo
import com.vayunmathur.everysync.core.network.recordChannel
import com.vayunmathur.everysync.core.sync.adapters.TodoKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Linux EDS sink tests against the file-mirror backend (no `libecal` on CI:
 * [EdsBridge.isAvailable] is false here, so every assertion runs against the
 * file mirror — the same merge contract the native swap preserves).
 *
 * Covers: full EDS-todos round-trip (`notes` + `createdAt` survive, tombstones
 * propagate as real rows), [EdsTodoSink] local edit → relay push, calendar /
 * contacts mirror pull + tombstone persistence, and the null-pull abort
 * (failed pull writes nothing, returns `ok=false`).
 *
 * A Linux device/CI run with `libecal-2.0` additionally needs: native-client
 * open + list/create/modify against a scratch EDS collection asserting the
 * VTODO/VCALENDAR field map in each sink's KDoc.
 */
class EdsSinkTest {

    private val vaultKey = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun `eds todos round-trip preserves notes and createdAt`() = runBlocking {
        val dir = tempDir()
        val sink = EdsTodoSink(dir, "v1")
        val todo = RemoteTodo(
            uid = "t1", title = "Buy milk", notes = "oat, not dairy",
            done = false, dueAt = 1_700_000_000_000L, position = 1.0,
            createdAt = 1_699_000_000_000L, updatedAt = 1_700_000_000_001L,
        )
        sink.upsert("v1", todo)
        val read = sink.listLocal("v1").single()
        assertEquals("oat, not dairy", read.notes)
        assertEquals(1_699_000_000_000L, read.createdAt)
        assertEquals(1_700_000_000_000L, read.dueAt)
        assertTrue(sink.listLocalOk)
    }

    @Test
    fun `eds todos soft-delete keeps tombstone and localChanges reports it`() = runBlocking {
        val dir = tempDir()
        val sink = EdsTodoSink(dir, "v1")
        sink.upsert("v1", RemoteTodo(uid = "t1", title = "A", updatedAt = 100L))
        sink.delete("v1", "t1")
        val rows = sink.listLocal("v1")
        assertEquals(1, rows.size)
        assertTrue(rows.single().deleted)
        val changes = sink.localChanges("v1", since = 0L)
        assertEquals(1, changes.size)
        assertTrue(changes.single().deleted)
    }

    @Test
    fun `eds sync pulls remote todos and pushes local edits`() = runBlocking {
        val dir = tempDir()
        val transport = FakeRelayTransport()
        val channel = TodoKind.channel("v1")
        val remoteTodo = RemoteTodo(
            uid = "r1", title = "Remote", notes = "from relay",
            createdAt = 500L, updatedAt = 1_000L,
        )
        transport.seed(channel, vaultKey, listOf(TodoKind.toJson(remoteTodo)))

        val local = EdsTodoSink(dir, "v1")
        local.upsert("v1", RemoteTodo(uid = "l1", title = "Local", notes = "mine", createdAt = 10L, updatedAt = 20L))

        val result = EdsSync(transport, dir).syncOnce("v1", vaultKey)
        assertTrue(result.todos.ok)

        val todos = EdsTodoSink(dir, "v1").listLocal("v1")
        assertEquals(setOf("r1", "l1"), todos.map { it.uid }.toSet())
        // The local row was pushed to the relay (remoteOut BEFORE local persist).
        val pushed = transport.appendedPlaintexts(channel, vaultKey)
            .mapNotNull { TodoKind.fromJson(it) }
        assertTrue(pushed.any { it.uid == "l1" })
        // Pulled row kept its envelope fields verbatim.
        assertEquals("from relay", todos.single { it.uid == "r1" }.notes)
    }

    @Test
    fun `eds sync propagates a pulled tombstone into the mirror`() = runBlocking {
        val dir = tempDir()
        val transport = FakeRelayTransport()
        val channel = TodoKind.channel("v1")
        val live = RemoteTodo(uid = "t9", title = "Gone", updatedAt = 100L)
        transport.seed(channel, vaultKey, listOf(TodoKind.toJson(live)))
        assertTrue(EdsSync(transport, dir).syncOnce("v1", vaultKey).todos.ok)
        assertFalse(EdsTodoSink(dir, "v1").listLocal("v1").single().deleted)

        val tombstone = live.copy(deleted = true, updatedAt = 200L)
        transport.seed(channel, vaultKey, listOf(TodoKind.toJson(tombstone)))
        assertTrue(EdsSync(transport, dir).syncOnce("v1", vaultKey).todos.ok)

        val rows = EdsTodoSink(dir, "v1").listLocal("v1")
        assertEquals(1, rows.size)
        assertTrue(rows.single().deleted)
    }

    @Test
    fun `null pull aborts with zero local writes`() = runBlocking {
        val dir = tempDir()
        val transport = FakeRelayTransport()
        val channel = TodoKind.channel("v1")
        transport.failPullChannels = setOf(channel)
        transport.seed(channel, vaultKey, listOf(TodoKind.toJson(RemoteTodo(uid = "x", updatedAt = 1L))))

        val result = EdsSync(transport, dir).syncOnce("v1", vaultKey)
        assertFalse(result.todos.ok)
        // Aborted cycle: seed was unreachable (failed pull), mirror untouched.
        assertTrue(EdsTodoSink(dir, "v1").listLocal("v1").isEmpty())
    }

    @Test
    fun `eds calendar mirror pulls events keyed by uid plus recurrence-id`() = runBlocking {
        val dir = tempDir()
        val transport = FakeRelayTransport()
        val channel = recordChannel("calendar", "v1")
        val master = com.vayunmathur.everysync.core.data.RemoteEvent(
            uid = "e1", calendarId = "cal", summary = "Standup",
            startMillis = 1_700_000_000_000L, endMillis = 1_700_000_030_000L,
            updatedAt = 100L,
        )
        val instance = master.copy(
            recurrenceId = "20240101T090000Z", summary = "Standup (moved)", updatedAt = 200L,
        )
        transport.seed(
            channel, vaultKey,
            listOf(
                com.vayunmathur.everysync.core.sync.adapters.CalendarKind.toJson(master),
                com.vayunmathur.everysync.core.sync.adapters.CalendarKind.toJson(instance),
            ),
        )

        val result = EdsSync(transport, dir).syncOnce("v1", vaultKey)
        assertTrue(result.calendar.ok)
        val rows = EdsCalendarSink(dir, "v1").listLocal("v1")
        assertEquals(2, rows.size)
        assertEquals(
            setOf("e1", "e1\u000020240101T090000Z"),
            rows.map { com.vayunmathur.everysync.core.sync.adapters.CalendarKind.syncId(it) }.toSet(),
        )
    }

    @Test
    fun `eds contacts mirror pulls vcard rows and tombstones survive`() = runBlocking {
        val dir = tempDir()
        val transport = FakeRelayTransport()
        val channel = recordChannel("contacts", "v1")
        val contact = com.vayunmathur.everysync.core.data.RemoteContact(
            uid = "c1", displayName = "Ada", firstName = "Ada",
            phones = listOf(
                com.vayunmathur.everysync.core.data.TypedValue("+1555", 2),
            ),
            updatedAt = 100L,
        )
        transport.seed(
            channel, vaultKey,
            listOf(com.vayunmathur.everysync.core.sync.adapters.ContactKind.toJson(contact)),
        )
        assertTrue(EdsSync(transport, dir).syncOnce("v1", vaultKey).contacts.ok)
        assertEquals("Ada", EdsContactsSink(dir, "v1").listLocal("v1").single().displayName)

        EdsContactsSink(dir, "v1").delete("v1", "c1")
        val rows = EdsContactsSink(dir, "v1").listLocal("v1")
        assertEquals(1, rows.size)
        assertTrue(rows.single().deleted)
    }

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "eds-test-${System.nanoTime()}").apply { mkdirs() }
}
