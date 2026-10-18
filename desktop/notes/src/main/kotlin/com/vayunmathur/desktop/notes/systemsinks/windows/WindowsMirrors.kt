package com.vayunmathur.desktop.notes.systemsinks.windows

import com.vayunmathur.everysync.core.data.RemoteContact
import com.vayunmathur.everysync.core.data.RemoteEvent
import com.vayunmathur.everysync.core.platform.LocalChange
import com.vayunmathur.everysync.core.sinks.CalendarSystemSink
import com.vayunmathur.everysync.core.sinks.ContactsSystemSink
import com.vayunmathur.everysync.core.sync.LocalReadException
import com.vayunmathur.everysync.core.sync.RelaySnapshot
import com.vayunmathur.everysync.core.sync.RelayStore
import com.vayunmathur.everysync.core.sync.adapters.CalendarKind
import com.vayunmathur.everysync.core.sync.adapters.ContactKind
import com.vayunmathur.desktop.notes.systemsinks.FileStoreIo
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Windows v1 system bridges — READ-ONLY.
 *
 * On Windows there is no writable sync-provider surface available to this
 * JVM shell (`AppointmentStore` / `ContactStore` expose no sync-adapter
 * contract like Android's `CALLER_IS_SYNCADAPTER` + `DIRTY`/`DELETED`, so
 * relay writes could never be told apart from user edits). These bridges
 * therefore MIRROR the relay channels into local read models only:
 *
 * - `WindowsCalendarMirror : CalendarSystemSink` mirrors
 *   `calendar:<vaultId>` — local queriable copy of the folded relay state.
 * - `WindowsContactsMirror : ContactsSystemSink` mirrors
 *   `contacts:<vaultId>` — same discipline.
 *
 * Read-only discipline (why [upsert]/[delete]/[purge] refuse local writes
 * but still accept merge writes):
 * - The merge driver calls the SAME [RelayStore] surface for both halves of
 *   the plan: `upsertLocal` (relay→local) and `deleteLocal`
 *   (merge-acknowledged cleanup). Those MUST apply — they persist what the
 *   relay already decided; blocking them would wedge the baseline forever.
 * - Direct [upsert]/[delete] (local-originated edits), however, are user
 *   writes with no native store to land in, so they are IGNORED (no-op):
 *   there is no local edit source on Windows v1, and silently accepting an
 *   edit callers believe persisted would be worse than refusing it.
 * - [localChanges] always returns empty: nothing local can change, so the
 *   merge never pushes from this side (`remoteOut` only ever carries
 *   adoptions of unidentified relay rows, never local edits).
 * - [purge] still drops rows + snapshots: vault-scoped teardown is not an
 *   edit — it is the same `deleteByVault` the merge issues on vault removal.
 *
 * A future writable Windows path (Graph-backed provider or a sync-adapter
 * equivalent) would flip [localChanges]/[upsert]/[delete] to real
 * implementations behind this same [CalendarSystemSink]/[ContactsSystemSink]
 * surface; the relay channel, tombstone semantics, and identity columns are
 * unchanged.
 *
 * Todos are NOT mirrored here: Windows renders the SAME `todos:<vaultId>`
 * channel inside the Notes UI via the existing `TodoView.todosAsNotes`
 * projection (read-write through `updateTodoFromNote`). A second todos copy
 * in this package would split the channel — so there is deliberately no
 * `WindowsTodoMirror`.
 */
class WindowsCalendarMirror(
    private val dataDir: File,
    private val vaultId: String,
) : CalendarSystemSink, RelayStore<RemoteEvent> {

    private val storeFile: File get() = File(dataDir, "win-calendar-$vaultId.json")
    private val snapshotFile: File get() = File(dataDir, "snapshots-win-calendar-$vaultId.tsv")
    private val rows = LinkedHashMap<String, RemoteEvent>()
    private val snapshots = LinkedHashMap<String, RelaySnapshot>()
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var lastReadOk: Boolean = true

    /**
     * Mirror of the folded relay state, tombstones included. Failed reads
     * throw [LocalReadException], never an empty list.
     */
    override suspend fun listLocal(vaultId: String): List<RemoteEvent> = mutex.withLock {
        loadLocked()
        lastReadOk = true
        rows.values.toList()
    }

    override val listLocalOk: Boolean get() = lastReadOk

    /**
     * Always empty (read-only v1): no local edit source exists, so the merge
     * never pushes from this side.
     */
    override suspend fun localChanges(vaultId: String, since: Long): List<LocalChange<RemoteEvent>> =
        emptyList()

    /**
     * No-op (read-only v1): local-originated writes have no native store to
     * land in. Merge-driven relay→local writes arrive via [upsertLocal],
     * never here.
     */
    override suspend fun upsert(vaultId: String, item: RemoteEvent) = Unit

    /**
     * No-op (read-only v1): see [upsert]. Merge-acknowledged cleanup arrives
     * via [deleteLocal].
     */
    override suspend fun delete(vaultId: String, syncId: String) = Unit

    /** Vault teardown: drops every row *and* its snapshots. */
    override suspend fun purge(vaultId: String) {
        mutex.withLock {
            rows.clear()
            snapshots.clear()
            storeFile.delete()
            snapshotFile.delete()
        }
    }

    // --- RelayStore (merge-driver surface: relay→local half applies) ---

    override suspend fun listLocal(): List<RemoteEvent> = listLocal(vaultId)

    /**
     * Applies the merge's relay→local half: persists what the relay already
     * decided (pulls, resurrections, relay-won tombstones). This is a mirror
     * update, not a local edit, so it applies even on read-only v1.
     */
    override suspend fun upsertLocal(items: List<RemoteEvent>) {
        mutex.withLock {
            loadLocked()
            for (item in items) {
                if (item.uid.isBlank()) continue
                rows[CalendarKind.syncId(item)] = item
            }
            saveLocked()
        }
    }

    /**
     * Merge-confirmed removals only: every id here is baseline-proved
     * acknowledged on both sides.
     */
    override suspend fun deleteLocal(items: List<RemoteEvent>) {
        mutex.withLock {
            loadLocked()
            for (item in items) rows.remove(CalendarKind.syncId(item))
            saveLocked()
        }
    }

    override suspend fun loadBaseline(): Map<String, RelaySnapshot> = mutex.withLock {
        snapshots.clear()
        snapshots.putAll(FileStoreIo.loadSnapshots(snapshotFile))
        snapshots.toMap()
    }

    override suspend fun saveSnapshots(snapshots: List<RelaySnapshot>) {
        mutex.withLock {
            this.snapshots.clear()
            this.snapshots.putAll(FileStoreIo.loadSnapshots(snapshotFile))
            for (s in snapshots) this.snapshots[s.syncId] = s
            FileStoreIo.saveSnapshots(snapshotFile, this.snapshots)
        }
    }

    override suspend fun removeSnapshots(syncIds: List<String>) {
        mutex.withLock {
            this.snapshots.clear()
            this.snapshots.putAll(FileStoreIo.loadSnapshots(snapshotFile))
            for (id in syncIds) this.snapshots.remove(id)
            FileStoreIo.saveSnapshots(snapshotFile, this.snapshots)
        }
    }

    override fun parseRemote(json: String): RemoteEvent? =
        CalendarKind.fromJson(json)?.takeIf { it.uid.isNotBlank() }

    override fun serializeForRemote(entity: RemoteEvent): String = CalendarKind.toJson(entity)

    private fun loadLocked() {
        val file = storeFile
        if (!file.isFile) return
        try {
            val decoded = json.decodeFromString<List<RemoteEvent>>(file.readText())
            rows.clear()
            for (event in decoded) {
                if (event.uid.isNotBlank()) rows[CalendarKind.syncId(event)] = event
            }
            lastReadOk = true
        } catch (_: Exception) {
            lastReadOk = false
            throw LocalReadException("WindowsCalendarMirror read failed for $vaultId")
        }
    }

    private fun saveLocked() {
        dataDir.mkdirs()
        storeFile.writeText(json.encodeToString(rows.values.toList()))
    }
}

/**
 * Windows v1 contacts mirror — READ-ONLY. Same discipline as
 * [WindowsCalendarMirror]: [localChanges] is always empty, direct
 * [upsert]/[delete] are no-ops, while the merge's relay→local half
 * ([upsertLocal]/[deleteLocal]) and vault teardown ([purge]) apply.
 */
class WindowsContactsMirror(
    private val dataDir: File,
    private val vaultId: String,
) : ContactsSystemSink, RelayStore<RemoteContact> {

    private val storeFile: File get() = File(dataDir, "win-contacts-$vaultId.json")
    private val snapshotFile: File get() = File(dataDir, "snapshots-win-contact-$vaultId.tsv")
    private val rows = LinkedHashMap<String, RemoteContact>()
    private val snapshots = LinkedHashMap<String, RelaySnapshot>()
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var lastReadOk: Boolean = true

    /**
     * Mirror of the folded relay state, tombstones included. Failed reads
     * throw [LocalReadException], never an empty list.
     */
    override suspend fun listLocal(vaultId: String): List<RemoteContact> = mutex.withLock {
        loadLocked()
        lastReadOk = true
        rows.values.toList()
    }

    override val listLocalOk: Boolean get() = lastReadOk

    /** Always empty (read-only v1): no local edit source exists. */
    override suspend fun localChanges(vaultId: String, since: Long): List<LocalChange<RemoteContact>> =
        emptyList()

    /** No-op (read-only v1): see [WindowsCalendarMirror.upsert]. */
    override suspend fun upsert(vaultId: String, item: RemoteContact) = Unit

    /** No-op (read-only v1): see [WindowsCalendarMirror.delete]. */
    override suspend fun delete(vaultId: String, syncId: String) = Unit

    /** Vault teardown: drops every row *and* its snapshots. */
    override suspend fun purge(vaultId: String) {
        mutex.withLock {
            rows.clear()
            snapshots.clear()
            storeFile.delete()
            snapshotFile.delete()
        }
    }

    // --- RelayStore (merge-driver surface: relay→local half applies) ---

    override suspend fun listLocal(): List<RemoteContact> = listLocal(vaultId)

    /**
     * Applies the merge's relay→local half (mirror update, not a local edit —
     * applies even on read-only v1).
     */
    override suspend fun upsertLocal(items: List<RemoteContact>) {
        mutex.withLock {
            loadLocked()
            for (item in items) {
                if (item.uid.isBlank()) continue
                rows[item.uid] = item
            }
            saveLocked()
        }
    }

    /**
     * Merge-confirmed removals only: every id here is baseline-proved
     * acknowledged on both sides.
     */
    override suspend fun deleteLocal(items: List<RemoteContact>) {
        mutex.withLock {
            loadLocked()
            for (item in items) rows.remove(item.uid)
            saveLocked()
        }
    }

    override suspend fun loadBaseline(): Map<String, RelaySnapshot> = mutex.withLock {
        snapshots.clear()
        snapshots.putAll(FileStoreIo.loadSnapshots(snapshotFile))
        snapshots.toMap()
    }

    override suspend fun saveSnapshots(snapshots: List<RelaySnapshot>) {
        mutex.withLock {
            this.snapshots.clear()
            this.snapshots.putAll(FileStoreIo.loadSnapshots(snapshotFile))
            for (s in snapshots) this.snapshots[s.syncId] = s
            FileStoreIo.saveSnapshots(snapshotFile, this.snapshots)
        }
    }

    override suspend fun removeSnapshots(syncIds: List<String>) {
        mutex.withLock {
            this.snapshots.clear()
            this.snapshots.putAll(FileStoreIo.loadSnapshots(snapshotFile))
            for (id in syncIds) this.snapshots.remove(id)
            FileStoreIo.saveSnapshots(snapshotFile, this.snapshots)
        }
    }

    override fun parseRemote(json: String): RemoteContact? =
        ContactKind.fromJson(json)?.takeIf { it.uid.isNotBlank() }

    override fun serializeForRemote(entity: RemoteContact): String = ContactKind.toJson(entity)

    private fun loadLocked() {
        val file = storeFile
        if (!file.isFile) return
        try {
            val decoded = json.decodeFromString<List<RemoteContact>>(file.readText())
            rows.clear()
            for (contact in decoded) {
                if (contact.uid.isNotBlank()) rows[contact.uid] = contact
            }
            lastReadOk = true
        } catch (_: Exception) {
            lastReadOk = false
            throw LocalReadException("WindowsContactsMirror read failed for $vaultId")
        }
    }

    private fun saveLocked() {
        dataDir.mkdirs()
        storeFile.writeText(json.encodeToString(rows.values.toList()))
    }
}
