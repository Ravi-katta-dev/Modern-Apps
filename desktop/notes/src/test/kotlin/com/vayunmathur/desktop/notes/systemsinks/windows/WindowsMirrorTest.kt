package com.vayunmathur.desktop.notes.systemsinks.windows

import com.vayunmathur.desktop.notes.TodoView
import com.vayunmathur.desktop.notes.systemsinks.FakeRelayTransport
import com.vayunmathur.everysync.core.data.RemoteContact
import com.vayunmathur.everysync.core.data.RemoteEvent
import com.vayunmathur.everysync.core.data.RemoteNote
import com.vayunmathur.everysync.core.data.RemoteTodo
import com.vayunmathur.everysync.core.network.recordChannel
import com.vayunmathur.everysync.core.sync.adapters.CalendarKind
import com.vayunmathur.everysync.core.sync.adapters.ContactKind
import com.vayunmathur.everysync.core.sync.adapters.TodoKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Windows v1 tests: read-only mirrors pull relay state, refuse local edits,
 * and the SAME `todos:<vaultId>` channel renders in the Notes UI through the
 * existing `TodoView.todosAsNotes` projection (no second todos copy).
 */
class WindowsMirrorTest {

    private val vaultKey = ByteArray(32) { (it + 7).toByte() }

    @Test
    fun `calendar mirror pulls relay state but ignores local upserts`() = runBlocking {
        val dir = tempDir()
        val transport = FakeRelayTransport()
        val channel = recordChannel("calendar", "v1")
        val event = RemoteEvent(
            uid = "e1", calendarId = "cal", summary = "Demo",
            startMillis = 1_700_000_000_000L, endMillis = 1_700_000_060_000L,
            updatedAt = 100L,
        )
        transport.seed(channel, vaultKey, listOf(CalendarKind.toJson(event)))

        val result = WindowsSync(transport, dir).syncOnce("v1", vaultKey)
        assertTrue(result.calendar.ok)
        assertEquals("Demo", WindowsCalendarMirror(dir, "v1").listLocal("v1").single().summary)

        // Local-originated writes are refused on read-only v1.
        WindowsCalendarMirror(dir, "v1").upsert(
            "v1",
            event.copy(summary = "Hacked", updatedAt = 999L),
        )
        WindowsCalendarMirror(dir, "v1").delete("v1", "e1")
        assertEquals("Demo", WindowsCalendarMirror(dir, "v1").listLocal("v1").single().summary)
        // …and nothing local is ever reported, so the merge never pushes.
        assertTrue(WindowsCalendarMirror(dir, "v1").localChanges("v1", 0L).isEmpty())
        assertTrue(transport.appendedPlaintexts(channel, vaultKey).isEmpty())
    }

    @Test
    fun `contacts mirror pulls relay state but ignores local deletes`() = runBlocking {
        val dir = tempDir()
        val transport = FakeRelayTransport()
        val channel = recordChannel("contacts", "v1")
        val contact = RemoteContact(uid = "c1", displayName = "Grace", updatedAt = 50L)
        transport.seed(channel, vaultKey, listOf(ContactKind.toJson(contact)))

        val result = WindowsSync(transport, dir).syncOnce("v1", vaultKey)
        assertTrue(result.contacts.ok)
        assertEquals("Grace", WindowsContactsMirror(dir, "v1").listLocal("v1").single().displayName)

        WindowsContactsMirror(dir, "v1").delete("v1", "c1")
        assertEquals(1, WindowsContactsMirror(dir, "v1").listLocal("v1").size)
        assertTrue(WindowsContactsMirror(dir, "v1").localChanges("v1", 0L).isEmpty())
    }

    @Test
    fun `windows todos render in Notes UI through the same channel projection`() = runBlocking {
        val dir = tempDir()
        // Same TodoView store the Notes shell syncs: write a todo, project it.
        val todos = TodoView(dir, "v1")
        todos.upsert(
            "v1",
            RemoteTodo(
                uid = "t1", title = "File taxes", notes = "before April",
                done = false, createdAt = 42L, updatedAt = 100L,
            ),
        )
        val projected = todos.todosAsNotes()
        assertEquals(1, projected.size)
        val note: RemoteNote = projected.single()
        assertEquals("todo:t1", note.uid)
        assertEquals("File taxes", note.title)
        assertTrue(note.content.contains("before April"))

        // A projection edit writes back onto the SAME todos-channel row.
        todos.updateTodoFromNote(note.copy(title = "File taxes NOW"))
        assertEquals("File taxes NOW", todos.listLocal("v1").single().title)
        assertEquals("before April", todos.listLocal("v1").single().notes)

        // Tombstoned todos project to nothing (their tombstone stays on-channel).
        todos.delete("v1", "t1")
        assertTrue(todos.todosAsNotes().isEmpty())
        assertTrue(todos.listLocal("v1").single().deleted)

        // The projection round-trips through the real envelope codec.
        val envelope = TodoKind.toJson(todos.listLocal("v1").single())
        assertEquals("t1", TodoKind.fromJson(envelope)?.uid)
        // …and the channel name is the shared todos channel (no second copy).
        assertEquals("todos:v1", TodoKind.channel("v1"))
    }

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "win-test-${System.nanoTime()}").apply { mkdirs() }
}
