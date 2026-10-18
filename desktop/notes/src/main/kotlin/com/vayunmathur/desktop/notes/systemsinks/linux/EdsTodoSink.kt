package com.vayunmathur.desktop.notes.systemsinks.linux

import com.vayunmathur.everysync.core.data.RemoteTodo
import com.vayunmathur.everysync.core.platform.LocalChange
import com.vayunmathur.everysync.core.sinks.TodoSystemSink
import com.vayunmathur.everysync.core.sync.LocalReadException
import com.vayunmathur.everysync.core.sync.RelaySnapshot
import com.vayunmathur.everysync.core.sync.RelayStore
import com.vayunmathur.everysync.core.sync.adapters.TodoKind
import com.vayunmathur.desktop.notes.systemsinks.FileStoreIo
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Linux `TodoSystemSink` over the EDS task list (`libecal` todos): syncs the
 * `todos:<vaultId>` relay channel into the user's EDS tasks collection.
 *
 * **BACKEND: file mirror (EDS native path is a stub); READ-WRITE.**
 * [EdsBridge.isAvailable] is false wherever no `libecal-2.0` links (CI,
 * Windows, bare containers), so this sink persists the channel to
 * `eds-todos-<vaultId>.json` under [dataDir] with the EXACT VTODO field
 * mapping below. Unlike the calendar/contacts bridges, this sink is fully
 * read-write on BOTH backends: local creates/edits/deletes flow to the relay
 * through the merge, and tombstones propagate as real rows.
 *
 * EDS VTODO field mapping (what the native path MUST persist per row, via
 * `E_CAL_CLIENT_SOURCE_TYPE_TASKS` clients):
 * - Identity: VTODO `UID` = relay `uid` (created with
 *   `e_cal_client_create_objects_sync`; read back with
 *   `e_cal_client_get_object_list_sync` sexp `"#t"` filtered to this vault's
 *   `X-EVERYSYNC-VAULT`).
 * - Title: VTODO `SUMMARY` = [RemoteTodo.title].
 * - Body: VTODO `DESCRIPTION` = [RemoteTodo.notes] — persisted VERBATIM
 *   (Phase 1 drift fix: `notes` is an envelope field AND a merge-hash input;
 *   dropping or trimming it breaks LWW convergence on that field).
 * - Done: VTODO `STATUS` = `COMPLETED` iff [RemoteTodo.done] (else `NEEDS-ACTION`).
 * - Due: VTODO `DUE` (UTC `Z` form) = [RemoteTodo.dueAt], absent when null.
 * - Created: VTODO `CREATED` (UTC `Z` form) = [RemoteTodo.createdAt] —
 *   persisted VERBATIM (same drift fix: `createdAt` feeds the adoption
 *   content key `todo\0<title>\0<createdAt>`; dropping it re-adopts every
 *   remote task as new on each cycle).
 * - Stamp: VTODO `LAST-MODIFIED` = relay `updatedAt` (ms), preserved verbatim
 *   on relay→EDS writes and read back on EDS→merge reads.
 * - Vault tag: `X-EVERYSYNC-VAULT: <vaultId>` extended property (mirrors
 *   Android `SYNC_DATA2`).
 * - Ordering: [RemoteTodo.position] is ordering-only (excluded from the merge
 *   hash) — persisted verbatim as `X-EVERYSYNC-POSITION`, never bumping
 *   `updatedAt` on a pure reorder.
 *
 * Tombstone rule (read-write soft-delete): [delete] flags the row (EDS-side:
 * component deleted mark); tombstones persist in [listLocal] until the merge
 * proves both sides acknowledged them. Only [RelayStore.deleteLocal]
 * hard-removes, for merge-acknowledged ids only. Absence without a tombstone
 * resurrects the relay copy — never deletes.
 */
class EdsTodoSink(
    private val dataDir: File,
    private val vaultId: String,
) : TodoSystemSink, RelayStore<RemoteTodo> {

    private val storeFile: File get() = File(dataDir, "eds-todos-$vaultId.json")
    private val snapshotFile: File get() = File(dataDir, "snapshots-eds-todo-$vaultId.tsv")
    private val rows = LinkedHashMap<String, RemoteTodo>()
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
    override suspend fun listLocal(vaultId: String): List<RemoteTodo> = mutex.withLock {
        loadLocked()
        lastReadOk = true
        rows.values.toList()
    }

    override val listLocalOk: Boolean get() = lastReadOk

    /** Rows with `updatedAt` past [since], tombstones included. */
    override suspend fun localChanges(vaultId: String, since: Long): List<LocalChange<RemoteTodo>> =
        mutex.withLock {
            loadLocked()
            rows.values.filter { it.updatedAt > since }.map {
                LocalChange(it.uid, it.updatedAt, it.deleted, it)
            }
        }

    /**
     * Idempotent upsert keyed on the relay sync identity, preserving the
     * pulled `updatedAt` and the full envelope (`notes`, `createdAt`).
     * `deleted = true` tombstones the row instead of removing it.
     */
    override suspend fun upsert(vaultId: String, item: RemoteTodo) {
        if (item.uid.isBlank()) return
        mutex.withLock {
            loadLocked()
            rows[item.uid] = item
            saveLocked()
        }
    }

    /**
     * Soft-delete: tombstones the row so the deletion propagates. Pulled
     * tombstones arrive via [upsert] as `deleted = true` envelopes, never as
     * raw deletes.
     */
    override suspend fun delete(vaultId: String, syncId: String) {
        mutex.withLock {
            loadLocked()
            val existing = rows[syncId] ?: return
            rows[syncId] = TodoKind.withUpdatedAt(
                TodoKind.withDeleted(existing, true),
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

    override suspend fun listLocal(): List<RemoteTodo> = listLocal(vaultId)

    override suspend fun upsertLocal(items: List<RemoteTodo>) {
        for (item in items) upsert(vaultId, item)
    }

    /**
     * Merge-confirmed removals only: every id here is baseline-proved
     * acknowledged on both sides.
     */
    override suspend fun deleteLocal(items: List<RemoteTodo>) {
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

    override fun parseRemote(json: String): RemoteTodo? =
        TodoKind.fromJson(json)?.takeIf { it.uid.isNotBlank() }

    override fun serializeForRemote(entity: RemoteTodo): String = TodoKind.toJson(entity)

    // --- File mirror persistence (JSON rows; snapshots via FileStoreIo) ---

    private fun loadLocked() {
        val file = storeFile
        if (!file.isFile) return
        try {
            val decoded = json.decodeFromString<List<RemoteTodo>>(file.readText())
            rows.clear()
            for (todo in decoded) {
                if (todo.uid.isNotBlank()) rows[todo.uid] = todo
            }
            lastReadOk = true
        } catch (_: Exception) {
            lastReadOk = false
            throw LocalReadException("EdsTodoSink read failed for $vaultId")
        }
    }

    private fun saveLocked() {
        dataDir.mkdirs()
        storeFile.writeText(json.encodeToString(rows.values.toList()))
    }
}
