package com.vayunmathur.desktop.notes.systemsinks.linux

import com.vayunmathur.everysync.core.data.RemoteEvent
import com.vayunmathur.everysync.core.platform.LocalChange
import com.vayunmathur.everysync.core.sinks.CalendarSystemSink
import com.vayunmathur.everysync.core.sync.LocalReadException
import com.vayunmathur.everysync.core.sync.RelaySnapshot
import com.vayunmathur.everysync.core.sync.RelayStore
import com.vayunmathur.everysync.core.sync.adapters.CalendarKind
import com.vayunmathur.desktop.notes.systemsinks.FileStoreIo
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Linux `CalendarSystemSink` over Evolution Data Server (`libecal`): syncs
 * the `calendar:<vaultId>` relay channel into the user's EDS calendar.
 *
 * **BACKEND: file mirror (EDS native path is a stub).** [EdsBridge.isAvailable]
 * is false wherever no `libecal-2.0` links (CI, Windows, bare containers), so
 * this sink persists the channel to `calendar-<vaultId>.json` under [dataDir]
 * with the EXACT EDS field mapping below. Swapping in the native client
 * changes storage, never semantics: the merge contract (tombstone-only delete,
 * pulled-`updatedAt` preserved verbatim, identity = UID + RECURRENCE-ID) is
 * identical on both backends.
 *
 * EDS field mapping (what the native path MUST persist per row):
 * - Sync identity: `ECalComponent` `UID` + `RECURRENCE-ID` override pair —
 *   the same pair `CalendarKind.syncId` joins with `\u0000`. Masters carry no
 *   RECURRENCE-ID; overridden instances carry the overridden occurrence's id.
 * - Stamp: `ECalComponent` `LAST-MODIFIED` (relay `updatedAt`, ms) —
 *   preserved verbatim on relay→EDS writes; read back on EDS→merge reads.
 * - Vault tag: `X-EVERYSYNC-VAULT: <vaultId>` extended property on every
 *   component this sink creates, so a shared EDS calendar holding several
 *   vaults stays partitionable (mirrors Android `SYNC_DATA2`).
 * - Body: the same iCalendar text `core.format.ICalendar.serialize` emits —
 *   SUMMARY/DESCRIPTION/LOCATION/DTSTART/DTEND (+ VALUE=DATE for all-day) and
 *   RRULE carried verbatim; recurring masters keep duration-instead-of-end per
 *   the shared serializer. `CalendarKind.canonical` strips UID/RECURRENCE-ID
 *   only for hashing — storage keeps both.
 * - Query: `e_cal_client_get_object_list_sync` with sexp `"#t"` filtered to
 *   components carrying this vault's `X-EVERYSYNC-VAULT`.
 *
 * Soft-delete rule (provider `DELETED`-flag semantics): delete paths NEVER
 * hard-remove a live row. `delete()` flags the mirror row (and, natively,
 * would set the EDS-side deleted mark on the component) so the tombstone
 * survives in [listLocal] until the merge proves both sides acknowledged it.
 * Only [RelayStore.deleteLocal] hard-removes, for merge-acknowledged ids only.
 * Absence without a tombstone is a missed read, never a delete (matches
 * `EntityKind.localDeletesAreAuthoritative = true` for calendar).
 */
class EdsCalendarSink(
    private val dataDir: File,
    private val vaultId: String,
) : CalendarSystemSink, RelayStore<RemoteEvent> {

    private val storeFile: File get() = File(dataDir, "calendar-$vaultId.json")
    private val snapshotFile: File get() = File(dataDir, "snapshots-calendar-$vaultId.tsv")
    private val rows = LinkedHashMap<String, RemoteEvent>()
    private val snapshots = LinkedHashMap<String, RelaySnapshot>()
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var lastReadOk: Boolean = true

    /** True on a Linux host with a linkable `libecal-2.0` (see [EdsBridge]). */
    val nativeAvailable: Boolean get() = EdsBridge.isAvailable()

    /**
     * Full local state, tombstones included. A failed read throws
     * [LocalReadException] (never an empty list): merging a failure as empty
     * would delete every baseline-present relay record.
     */
    override suspend fun listLocal(vaultId: String): List<RemoteEvent> = mutex.withLock {
        loadLocked()
        lastReadOk = true
        rows.values.toList()
    }

    override val listLocalOk: Boolean get() = lastReadOk

    /** Rows with `updatedAt` past [since], tombstones included. */
    override suspend fun localChanges(vaultId: String, since: Long): List<LocalChange<RemoteEvent>> =
        mutex.withLock {
            loadLocked()
            rows.values.filter { it.updatedAt > since }.map {
                LocalChange(CalendarKind.syncId(it), it.updatedAt, it.deleted, it)
            }
        }

    /**
     * Idempotent upsert keyed on UID + RECURRENCE-ID ([CalendarKind.syncId]),
     * preserving the pulled `updatedAt`. `deleted = true` flags the row
     * instead of removing it.
     */
    override suspend fun upsert(vaultId: String, item: RemoteEvent) {
        if (item.uid.isBlank()) return
        mutex.withLock {
            loadLocked()
            rows[CalendarKind.syncId(item)] = item
            saveLocked()
        }
    }

    /**
     * Soft-delete: flags the row (EDS-side: component deleted mark). Pulled
     * tombstones arrive via [upsert] as `deleted = true` envelopes, never as
     * raw deletes.
     */
    override suspend fun delete(vaultId: String, syncId: String) {
        mutex.withLock {
            loadLocked()
            val existing = rows[syncId] ?: return
            rows[syncId] = CalendarKind.withUpdatedAt(
                CalendarKind.withDeleted(existing, true),
                maxOf(existing.updatedAt, System.currentTimeMillis()),
            )
            saveLocked()
        }
    }

    /** Drops every row *and* its snapshots for this vault. */
    override suspend fun purge(vaultId: String) {
        mutex.withLock {
            rows.clear()
            snapshots.clear()
            storeFile.delete()
            snapshotFile.delete()
        }
    }

    // --- RelayStore (merge-driver surface) ---

    override suspend fun listLocal(): List<RemoteEvent> = listLocal(vaultId)

    override suspend fun upsertLocal(items: List<RemoteEvent>) {
        for (item in items) upsert(vaultId, item)
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

    // --- File mirror persistence (JSON rows; snapshots via FileStoreIo) ---

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
            throw LocalReadException("EdsCalendarSink read failed for $vaultId")
        }
    }

    private fun saveLocked() {
        dataDir.mkdirs()
        storeFile.writeText(json.encodeToString(rows.values.toList()))
    }
}
