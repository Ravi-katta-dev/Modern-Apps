package com.vayunmathur.everysync.core.sync

import com.vayunmathur.everysync.core.platform.SecureStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * [RelaySnapshot] list persistence over any [SecureStore], for `RelayStore`
 * adapters whose driver is [com.vayunmathur.everysync.core.provider.SyncDriver].
 *
 * One instance covers one `(vaultId, kind)` pair and persists under key
 * `relay_baseline:<vault>:<kind>` (JSON array of [RelaySnapshot] via
 * kotlinx.serialization) — the layout `RelayCursorStore` documents, so values
 * created here are adoptable by the Android shells unchanged.
 *
 * API mirrors
 * [com.vayunmathur.everysync.core.provider.MemoryBaselineStore.load/save/remove]
 * (which stays for tests and drivers that manage their own persistence), but
 * suspending and durable: `save` upserts by `syncId`, `remove` drops ids and
 * deletes the key when the baseline empties.
 *
 * Corrupt/missing payloads load as an empty baseline (self-healing: the next
 * successful merge re-baselines; the merge never treats a read failure as a
 * delete, so this direction is loss-safe).
 */
class SnapshotFileStore(
    private val store: SecureStore,
    private val vaultId: String,
    private val kind: String,
) {
    companion object {
        /** Key layout shared with the Android stores. */
        fun key(vaultId: String, kind: String): String = "relay_baseline:$vaultId:$kind"
    }

    private val key = key(vaultId, kind)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    /** Loads the baseline; empty when nothing was persisted (or it is corrupt). */
    suspend fun load(): Map<String, RelaySnapshot> = mutex.withLock { currentLocked() }

    /** Upserts [list] by `syncId`, preserving previously baselined ids. */
    suspend fun save(list: List<RelaySnapshot>) {
        if (list.isEmpty()) return
        mutex.withLock {
            val merged = currentLocked().toMutableMap()
            for (s in list) merged[s.syncId] = s
            persistLocked(merged.values.toList())
        }
    }

    /** Drops [ids] from the baseline; deletes the key when it empties. */
    suspend fun remove(ids: List<String>) {
        if (ids.isEmpty()) return
        mutex.withLock {
            val merged = currentLocked().toMutableMap()
            var changed = false
            for (id in ids) if (merged.remove(id) != null) changed = true
            if (!changed) return
            persistLocked(merged.values.toList())
        }
    }

    /** Lock must be held. */
    private suspend fun currentLocked(): Map<String, RelaySnapshot> {
        val raw = store.getString(key) ?: return emptyMap()
        if (raw.isBlank()) return emptyMap()
        return runCatching { json.decodeFromString<List<RelaySnapshot>>(raw) }
            .getOrNull()?.associateBy { it.syncId } ?: emptyMap()
    }

    /** Lock must be held. */
    private suspend fun persistLocked(all: List<RelaySnapshot>) {
        if (all.isEmpty()) {
            store.delete(key)
        } else {
            store.setString(key, json.encodeToString(all))
        }
    }
}
