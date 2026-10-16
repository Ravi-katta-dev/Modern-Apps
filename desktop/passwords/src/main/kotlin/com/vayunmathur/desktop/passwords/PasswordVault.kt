package com.vayunmathur.desktop.passwords

import com.vayunmathur.everysync.core.data.RemotePassword
import com.vayunmathur.everysync.core.platform.LocalChange
import com.vayunmathur.everysync.core.platform.SyncSink
import com.vayunmathur.everysync.core.sync.LocalReadException
import com.vayunmathur.everysync.core.sync.RelaySnapshot
import com.vayunmathur.everysync.core.sync.RelayStore
import com.vayunmathur.everysync.core.sync.adapters.PasswordKind
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * JVM mirror of the passwords `RelayPasswordProvider` relay semantics, in-process.
 *
 * Op semantics preserved verbatim from `RelayPasswordProvider` / `PasswordSink`:
 * - full-state list → [listLocal]: live rows only (passwords hard-delete, so no
 *   tombstone filtering is ever needed — deleted rows simply do not exist).
 * - `upsertRaw` → [upsert]: idempotent on the relay sync identity
 *   ([RemotePassword.uid]); pulled `updatedAt` timestamps are preserved verbatim
 *   so LWW stays comparable across sides. A `deleted = true` envelope
 *   hard-deletes the row instead of storing it.
 * - single delete (`syncId = ?`) → [delete]: hard-deletes the row. The baseline
 *   snapshot is intentionally retained so the merge reads "baseline present +
 *   row absent" as a local delete and propagates a tombstone to the relay
 *   (see `PasswordKind.localDeletesAreAuthoritative = false`).
 * - `deleteByVault` → [purge]: drops every row *and* its snapshots.
 *
 * Hard-delete rule: delete paths NEVER write tombstone rows. Tombstones live
 * only on the relay side as `deleted = true` envelopes, and merges only ever
 * delete through those explicit envelopes (see `RelayMerge`: absence without a
 * tombstone never deletes). Only [RelayStore.deleteLocal] removes rows on the
 * merge's behalf, and only for ids the merge proved fully acknowledged.
 */
class PasswordVault(
    private val dataDir: File,
    private val vaultId: String,
) : SyncSink<RemotePassword>, RelayStore<RemotePassword> {

    private val storeFile: File get() = File(dataDir, "passwords-$vaultId.json")
    private val snapshotFile: File get() = File(dataDir, "snapshots-password-$vaultId.tsv")
    private val rows = LinkedHashMap<String, RemotePassword>()
    private val snapshots = LinkedHashMap<String, RelaySnapshot>()
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var lastReadOk: Boolean = true

    // --- SyncSink (provider-shaped surface) ---

    /**
     * Full local state: live rows only. A failed read surfaces as a thrown
     * [LocalReadException] (never an empty list): merging a read failure as
     * empty would delete every baseline-present relay record.
     */
    override suspend fun listLocal(vaultId: String): List<RemotePassword> = mutex.withLock {
        loadLocked()
        lastReadOk = true
        rows.values.filter { !it.deleted }
    }

    override val listLocalOk: Boolean get() = lastReadOk

    /**
     * `querySince` semantics: rows with `updatedAt` past [since]. Passwords
     * never report tombstones (hard deletes); a delete is observed as
     * "present at baseline, absent now" by the merge.
     */
    override suspend fun localChanges(vaultId: String, since: Long): List<LocalChange<RemotePassword>> =
        mutex.withLock {
            loadLocked()
            rows.values.filter { !it.deleted && it.updatedAt > since }.map {
                LocalChange(it.uid, it.updatedAt, false, it)
            }
        }

    /**
     * `upsertRaw` semantics: idempotent upsert keyed on the relay sync
     * identity, preserving the pulled `updatedAt`. A payload with
     * `deleted = true` hard-deletes the row instead (snapshot retained — see
     * [delete]). Merge-driven pulled tombstones ALWAYS arrive here as
     * `deleted = true` envelopes via upsert, never as raw deletes.
     */
    override suspend fun upsert(vaultId: String, item: RemotePassword) {
        if (item.uid.isBlank()) return
        mutex.withLock {
            loadLocked()
            if (item.deleted) {
                rows.remove(item.uid)
            } else {
                rows[item.uid] = item
            }
            saveLocked()
        }
    }

    /**
     * Single-delete semantics: hard-deletes the row but keeps its snapshot —
     * the relay merge reads that as a deletion and propagates a tombstone.
     */
    override suspend fun delete(vaultId: String, syncId: String) {
        mutex.withLock {
            loadLocked()
            rows.remove(syncId)
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

    override suspend fun listLocal(): List<RemotePassword> = listLocal(vaultId)

    override suspend fun upsertLocal(items: List<RemotePassword>) {
        for (item in items) upsert(vaultId, item)
    }

    /**
     * Merge-confirmed removals only: every id here carries a baseline proving
     * both sides acknowledged the tombstone, so hard-removal is safe. Live
     * rows are NEVER removed through any other path.
     */
    override suspend fun deleteLocal(items: List<RemotePassword>) {
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

    override fun parseRemote(json: String): RemotePassword? =
        PasswordKind.fromJson(json)?.takeIf { it.uid.isNotBlank() }

    override fun serializeForRemote(entity: RemotePassword): String = PasswordKind.toJson(entity)

    // --- File persistence (JSON rows, TSV snapshots) ---

    private fun loadLocked() {
        val file = storeFile
        if (!file.isFile) return
        try {
            val decoded = json.decodeFromString<List<RemotePassword>>(file.readText())
            rows.clear()
            for (entry in decoded) {
                if (entry.uid.isNotBlank() && !entry.deleted) rows[entry.uid] = entry
            }
            lastReadOk = true
        } catch (_: Exception) {
            lastReadOk = false
            throw LocalReadException("PasswordVault read failed for $vaultId")
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
