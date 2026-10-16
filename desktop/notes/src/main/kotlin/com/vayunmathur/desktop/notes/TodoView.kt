package com.vayunmathur.desktop.notes

import com.vayunmathur.everysync.core.data.RemoteNote
import com.vayunmathur.everysync.core.data.RemoteTodo
import com.vayunmathur.everysync.core.platform.LocalChange
import com.vayunmathur.everysync.core.platform.SyncSink
import com.vayunmathur.everysync.core.sync.LocalReadException
import com.vayunmathur.everysync.core.sync.RelaySnapshot
import com.vayunmathur.everysync.core.sync.RelayStore
import com.vayunmathur.everysync.core.sync.adapters.TodoKind
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * JVM mirror of the todo `RelayTodoProvider` relay semantics, in-process,
 * plus the Windows todos-view projection into the Notes UI.
 *
 * Column/op semantics preserved verbatim from `RelayTodoProvider`:
 * - `querySince` → [localChanges]: rows with `updatedAt` past `since`,
 *   tombstones included.
 * - `upsertRaw` → [upsert]: idempotent on the relay sync identity
 *   ([RemoteTodo.uid]); pulled `updatedAt` preserved verbatim for LWW.
 * - single delete → [delete]: tombstones the row for propagation.
 * - `deleteByVault` → [purge]: drops every row *and* its snapshots.
 *
 * Soft-delete rule (tombstone-only delete): identical to [NoteVault] — delete
 * paths tombstone, never hard-remove; tombstones persist in [listLocal] so
 * absence-without-tombstone is a missed read, never a delete (see
 * `EntityKind.localDeletesAreAuthoritative`, which holds for todos). Only
 * [RelayStore.deleteLocal] hard-removes, for merge-acknowledged ids only.
 *
 * Reorder-is-not-edit: [RemoteTodo.position] is stripped from the merge hash
 * (see `TodoKind.canonical`), so position persists verbatim and a pure
 * reorder never bumps `updatedAt`.
 *
 * Phase 1 drift note: [RemoteTodo.notes] and [RemoteTodo.createdAt] are part
 * of the envelope AND the merge hash (see `TodoKind.canonical`) — this store
 * persists both verbatim, so LWW on those fields converges locally.
 *
 * Windows todos-view: on Windows there is no system task provider, so the
 * Notes shell renders the `todos:<vaultId>` channel inside the Notes UI via
 * [todosAsNotes]. Each non-deleted todo projects to one read-model
 * [RemoteNote] (uid namespaced `"todo:<uid>"`, title = task title, content =
 * [RemoteTodo.notes] with a done/due header line). This is a PROJECTION, not
 * a second copy: edits write back through [updateTodoFromNote], which parses
 * the projection back onto the SAME `todos:<vaultId>` channel row. Deleted
 * todos project to nothing (their tombstone stays on the todos channel).
 *
 * Linux future: a real task provider exists on Linux (EDS via libecal). When
 * a libecal JVM binding lands, replace the projection with a two-way EDS
 * adapter behind this same [SyncSink] surface; the relay channel and
 * tombstone semantics stay unchanged.
 */
class TodoView(
    private val dataDir: File,
    private val vaultId: String,
) : SyncSink<RemoteTodo>, RelayStore<RemoteTodo> {

    private val storeFile: File get() = File(dataDir, "todos-$vaultId.json")
    private val snapshotFile: File get() = File(dataDir, "snapshots-todo-$vaultId.tsv")
    private val rows = LinkedHashMap<String, RemoteTodo>()
    private val snapshots = LinkedHashMap<String, RelaySnapshot>()
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var lastReadOk: Boolean = true

    // --- SyncSink (provider-shaped surface) ---

    /**
     * Full local state, tombstones included. Failed reads throw
     * [LocalReadException], never an empty list (see [NoteVault.listLocal]).
     */
    override suspend fun listLocal(vaultId: String): List<RemoteTodo> = mutex.withLock {
        loadLocked()
        lastReadOk = true
        rows.values.toList()
    }

    override val listLocalOk: Boolean get() = lastReadOk

    /** `querySince` semantics: rows past [since], tombstones included. */
    override suspend fun localChanges(vaultId: String, since: Long): List<LocalChange<RemoteTodo>> =
        mutex.withLock {
            loadLocked()
            rows.values.filter { it.updatedAt > since }.map {
                LocalChange(it.uid, it.updatedAt, it.deleted, it)
            }
        }

    /**
     * `upsertRaw` semantics: idempotent on [RemoteTodo.uid], preserving the
     * pulled `updatedAt`. `deleted = true` tombstones instead of removing.
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
     * Single-delete semantics: tombstones the row. Pulled tombstones arrive
     * via [upsert] as `deleted = true` envelopes, never as raw deletes.
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

    /** `deleteByVault` semantics: drops every row *and* its snapshots. */
    override suspend fun purge(vaultId: String) {
        mutex.withLock {
            rows.clear()
            snapshots.clear()
            storeFile.delete()
            snapshotFile.delete()
        }
    }

    // --- Windows todos-view projection (render todos inside Notes UI) ---

    /**
     * Read-model projection of the todos channel as notes for the Windows
     * Notes UI. Live (non-deleted) todos only; tombstones project to nothing.
     * The uid is namespaced `"todo:<uid>"` so projected rows never collide
     * with real note uids.
     */
    suspend fun todosAsNotes(): List<RemoteNote> = mutex.withLock {
        loadLocked()
        rows.values.filter { !it.deleted }.sortedBy { it.position }.map { it.asNote() }
    }

    /**
     * Writes a projection edit back onto the SAME todos-channel row:
     * re-parses the note body into ([RemoteTodo.title], [RemoteTodo.notes])
     * and upserts the todo with a fresh local `updatedAt`. Unknown
     * (non-projected) note uids are ignored — real notes go through
     * [NoteVault], never here.
     */
    suspend fun updateTodoFromNote(note: RemoteNote) {
        val uid = note.uid.removePrefix(TODO_NOTE_PREFIX)
        if (uid == note.uid) return
        mutex.withLock {
            loadLocked()
            val existing = rows[uid] ?: return
            val (title, notes) = splitProjection(note)
            rows[uid] = existing.copy(
                title = title,
                notes = notes,
                updatedAt = maxOf(existing.updatedAt, System.currentTimeMillis()),
            )
            saveLocked()
        }
    }

    // --- RelayStore (merge-driver surface) ---

    override suspend fun listLocal(): List<RemoteTodo> = listLocal(vaultId)

    override suspend fun upsertLocal(items: List<RemoteTodo>) {
        for (item in items) upsert(vaultId, item)
    }

    /**
     * Merge-confirmed removals only (see [NoteVault.deleteLocal]): every id
     * here is baseline-proved acknowledged on both sides.
     */
    override suspend fun deleteLocal(items: List<RemoteTodo>) {
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

    override fun parseRemote(json: String): RemoteTodo? =
        TodoKind.fromJson(json)?.takeIf { it.uid.isNotBlank() }

    override fun serializeForRemote(entity: RemoteTodo): String = TodoKind.toJson(entity)

    // --- File persistence ---

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
            throw LocalReadException("TodoView read failed for $vaultId")
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

    companion object {
        const val TODO_NOTE_PREFIX = "todo:"

        private fun RemoteTodo.asNote(): RemoteNote {
            val header = buildString {
                if (done) append("[done]") else append("[todo]")
                dueAt?.let { append(" due=$it") }
            }
            val body = if (notes.isBlank()) header else "$header\n\n$notes"
            return RemoteNote(
                uid = TODO_NOTE_PREFIX + uid,
                title = title,
                content = body,
                position = position,
                updatedAt = updatedAt,
            )
        }

        private fun splitProjection(note: RemoteNote): Pair<String, String> {
            val lines = note.content.lines()
            val body = if (lines.isNotEmpty() && lines[0].startsWith("[")) {
                lines.drop(1).joinToString("\n").trimStart('\n')
            } else {
                note.content
            }
            return note.title to body
        }
    }
}
