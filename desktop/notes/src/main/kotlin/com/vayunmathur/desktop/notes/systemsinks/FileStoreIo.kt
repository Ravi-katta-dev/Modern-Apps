package com.vayunmathur.desktop.notes.systemsinks

import com.vayunmathur.everysync.core.sync.RelaySnapshot
import java.io.File

/**
 * Shared file persistence for the desktop system-sink providers (Linux EDS
 * bridges + Windows read-only mirrors).
 *
 * Snapshot TSV shape is verbatim the [com.vayunmathur.desktop.notes.NoteVault]
 * / `TodoView` / `PasswordVault` format — 7 tab-separated fields
 * (`syncId, kind, contentHash, localUpdatedAt, remoteModified, lastSyncedAt,
 * deleted`) — so baselines stay comparable across shells. Row JSON stays
 * per-sink (each sink documents its own op semantics); only the snapshot
 * encoding is shared, because a drifted snapshot format would silently break
 * the merge's delete-vs-absence bookkeeping.
 */
internal object FileStoreIo {

    fun loadSnapshots(file: File): LinkedHashMap<String, RelaySnapshot> {
        val out = LinkedHashMap<String, RelaySnapshot>()
        if (!file.isFile) return out
        for (line in file.readLines()) {
            val parts = line.split("\t")
            if (parts.size != 7) continue
            out[parts[0]] = RelaySnapshot(
                syncId = parts[0],
                kind = parts[1],
                contentHash = parts[2],
                localUpdatedAt = parts[3].toLongOrNull() ?: 0L,
                remoteModified = parts[4].toLongOrNull() ?: 0L,
                lastSyncedAt = parts[5].toLongOrNull() ?: 0L,
                deleted = parts[6] == "1",
            )
        }
        return out
    }

    fun saveSnapshots(file: File, snapshots: Map<String, RelaySnapshot>) {
        file.parentFile?.mkdirs()
        file.writeText(
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
