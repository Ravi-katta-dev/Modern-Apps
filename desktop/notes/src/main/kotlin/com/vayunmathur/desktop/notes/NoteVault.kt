package com.vayunmathur.desktop.notes

import com.vayunmathur.everysync.core.data.RemoteNote
import com.vayunmathur.everysync.core.platform.LocalChange
import com.vayunmathur.everysync.core.platform.SyncSink
import com.vayunmathur.everysync.core.sync.LocalReadException
import com.vayunmathur.everysync.core.sync.RelaySnapshot
import com.vayunmathur.everysync.core.sync.RelayStore
import com.vayunmathur.everysync.core.sync.adapters.NoteKind
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * JVM mirror of the notes `RelayNoteProvider` relay semantics, in-process.
 *
 * Column/op semantics preserved verbatim from `RelayNoteProvider`:
 * - `querySince` → [localChanges]: rows with `updatedAt` past `since`,
 *   tombstones included.
 * - `upsertRaw` → [upsert]: idempotent on the relay sync identity
 *   ([RemoteNote.uid]); pulled `updatedAt` timestamps are preserved verbatim
 *   so LWW stays comparable across sides.
 * - single delete → [delete]: tombstones the row so the deletion propagates
 *   via the next [localChanges] read.
 * - `deleteByVault` → [purge]: drops every row *and* its snapshots.
 *
 * Soft-delete rule (tombstone-only delete): delete paths NEVER hard-remove a
 * live row. Tombstones persist in [listLocal] output so the merge can tell "we
 * deleted this" apart from "we never saw this" — an id absent WITHOUT a
 * tombstone is a missed read, never a delete (see
 * `EntityKind.localDeletesAreAuthoritative`, which holds for notes). Only
 * [RelayStore.deleteLocal] hard-removes, and only for ids the merge already
 * proved fully acknowledged on both sides.
 *
 * Reorder-is-not-edit: [RemoteNote.position] is stripped from the merge hash
 * (see `NoteKind.canonical`), so this store persists position verbatim and
 * never bumps `updatedAt` for a pure reorder.
 */
class NoteVault(
    private val dataDir: File,
    private val vaultId: String,
) : SyncSink<RemoteNote>, RelayStore<RemoteNote> {

    private val storeFile: File get() = File(dataDir, "notes-$vaultId.json")
    private val snapshotFile: File get() = File(dataDir, "snapshots-note-$vaultId.tsv")
    private val rows = LinkedHashMap<String, RemoteNote>()
    private val snapshots = LinkedHashMap<String, RelaySnapshot>()
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var lastReadOk: Boolean = true

    // --- SyncSink (provider-shaped surface) ---

    /**
     * Full local state, tombstones included. A failed read surfaces as a
     * thrown [LocalReadException] (never an empty list): merging a read
     * failure as empty would delete every baseline-present relay record.
     */
    override suspend fun listLocal(vaultId: String): List<RemoteNote> = mutex.withLock {
        loadLocked()
        lastReadOk = true
        rows.values.toList()
    }

    override val listLocalOk: Boolean get() = lastReadOk

    /** `querySince` semantics: rows past [since], tombstones included. */
    override suspend fun localChanges(vaultId: String, since: Long): List<LocalChange<RemoteNote>> =
        mutex.withLock {
            loadLocked()
            rows.values.filter { it.updatedAt > since }.map {
                LocalChange(it.uid, it.updatedAt, it.deleted, it)
            }
        }

    /**
     * `upsertRaw` semantics: idempotent upsert keyed on the relay sync
     * identity, preserving the pulled `updatedAt`. A payload with
     * `deleted = true` tombstones the row instead of removing it.
     */
    override suspend fun upsert(vaultId: String, item: RemoteNote) {
        if (item.uid.isBlank()) return
        mutex.withLock {
            loadLocked()
            rows[item.uid] = item
            saveLocked()
        }
    }

    /**
     * Single-delete semantics: tombstones the row (soft-delete). The tombstone
     * survives in [listLocal] until the merge proves both sides acknowledged
     * it. Merge-driven pulled tombstones ALWAYS arrive here as
     * `deleted = true` envelopes via [upsert], never as raw deletes.
     */
    override suspend fun delete(vaultId: String, syncId: String) {
        mutex.withLock {
            loadLocked()
            val existing = rows[syncId] ?: return
            rows[syncId] = NoteKind.withUpdatedAt(
                NoteKind.withDeleted(existing, true),
                maxOf(existing.updatedAt, System.currentTimeMillis()),
            )
            saveLocked()
        }
    }

    /** `deleteByVault` semantics: drops every row *and* its snapshots. */
    override suspend fun purge(vaultId: String) {
        mutex.withLock {
            rows.clear()
            snapshots.clear()
            storeFile.delete()
            snapshotFile.delete()
        }
    }

    // --- RelayStore (merge-driver surface) ---

    override suspend fun listLocal(): List<RemoteNote> = listLocal(vaultId)

    override suspend fun upsertLocal(items: List<RemoteNote>) {
        for (item in items) upsert(vaultId, item)
    }

    /**
     * Merge-confirmed removals only: every id here carries a baseline proving
     * both sides acknowledged the tombstone, so hard-removal is safe. Live
     * rows are NEVER removed through any other path.
     */
    override suspend fun deleteLocal(items: List<RemoteNote>) {
        mutex.withLock {
            loadLocked()
            for (item in items) rows.remove(item.uid)
            saveLocked()
        }
    }

    override suspend fun loadBaseline(): Map<String, RelaySnapshot> = mutex.withLock {
        loadSnapshotsLocked()
        snapshots.toMap()
    }

    override suspend fun saveSnapshots(snapshots: List<RelaySnapshot>) {
        mutex.withLock {
            loadSnapshotsLocked()
            for (s in snapshots) this.snapshots[s.syncId] = s
            saveSnapshotsLocked()
        }
    }

    override suspend fun removeSnapshots(syncIds: List<String>) {
        mutex.withLock {
            loadSnapshotsLocked()
            for (id in syncIds) snapshots.remove(id)
            saveSnapshotsLocked()
        }
    }

    override fun parseRemote(json: String): RemoteNote? =
        NoteKind.fromJson(json)?.takeIf { it.uid.isNotBlank() }

    override fun serializeForRemote(entity: RemoteNote): String = NoteKind.toJson(entity)

    // --- File persistence (JSON rows, TSV snapshots) ---

    private fun loadLocked() {
        val file = storeFile
        if (!file.isFile) return
        try {
            val decoded = json.decodeFromString<List<RemoteNote>>(file.readText())
            rows.clear()
            for (note in decoded) {
                if (note.uid.isNotBlank()) rows[note.uid] = note
            }
            lastReadOk = true
        } catch (_: Exception) {
            lastReadOk = false
            throw LocalReadException("NoteVault read failed for $vaultId")
        }
    }

    private fun saveLocked() {
        dataDir.mkdirs()
        storeFile.writeText(json.encodeToString(rows.values.toList()))
    }

    private fun loadSnapshotsLocked() {
        val file = snapshotFile
        if (!file.isFile) return
        snapshots.clear()
        for (line in file.readLines()) {
            val parts = line.split("\t")
            if (parts.size != 7) continue
            snapshots[parts[0]] = RelaySnapshot(
                syncId = parts[0],
                kind = parts[1],
                contentHash = parts[2],
                localUpdatedAt = parts[3].toLongOrNull() ?: 0L,
                remoteModified = parts[4].toLongOrNull() ?: 0L,
                lastSyncedAt = parts[5].toLongOrNull() ?: 0L,
                deleted = parts[6] == "1",
            )
        }
    }

    private fun saveSnapshotsLocked() {
        dataDir.mkdirs()
        snapshotFile.writeText(
            snapshots.values.joinToString("\n") {
                listOf(
                    it.syncId, it.kind, it.contentHash,
                    it.localUpdatedAt.toString(), it.remoteModified.toString(),
                    it.lastSyncedAt.toString(), if (it.deleted) "1" else "0",
                ).joinToString("\t")
            },
        )
    }
}
