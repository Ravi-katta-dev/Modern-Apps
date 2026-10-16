package com.vayunmathur.everysync.core.provider

import com.vayunmathur.everysync.core.sync.EntityKind
import com.vayunmathur.everysync.core.sync.LocalReadException
import com.vayunmathur.everysync.core.sync.RelayMerge
import com.vayunmathur.everysync.core.sync.RelaySnapshot
import com.vayunmathur.everysync.core.sync.RelayStore

/**
 * A Context-free E2EE sync provider: drives one vault type's merge cycle
 * against an injected [RelayStore]. The Android `E2eeProvider` becomes a thin
 * adapter that builds these stores from its sinks; desktop/iOS providers do
 * the same from theirs.
 */
interface SyncProvider {
    val id: String
    val displayName: String
    val capabilities: Set<DataType>

    /** Run one sync pass in the requested [direction]. */
    suspend fun sync(direction: SyncDirection)
}

/**
 * Context-free merge driver shared by every provider on every target.
 *
 * Driver contract (the relay is an append-only log — records are never
 * rewritten, only appended):
 *  0. A null [remoteEntities] (failed pull) aborts the cycle — never merges.
 *  1. [remoteEntities] must be the FULL folded state (tombstones included).
 *  2. `remoteOut` is serialized via [RelayStore.serializeForRemote] and
 *     appended to the relay BEFORE persisting locally.
 *  3. `applyLocal` persists the local half.
 */
object SyncDriver {
    suspend fun <T> drive(
        kind: EntityKind<T>,
        store: RelayStore<T>,
        remoteEntities: List<T>?,
        appendRemote: suspend (items: List<String>) -> Boolean,
        now: Long,
    ): Boolean {
        if (remoteEntities == null) return false
        val local = try {
            store.listLocal()
        } catch (_: LocalReadException) {
            return false
        }
        if (!store.listLocalOk) return false
        val baseline = store.loadBaseline()
        val plan = RelayMerge.merge(kind, local, remoteEntities, baseline, now)
        if (plan.remoteOut.isNotEmpty()) {
            val envelopes = plan.remoteOut.map { store.serializeForRemote(it) }
            if (!appendRemote(envelopes)) return false
        }
        RelayMerge.applyLocal(store, plan)
        return true
    }
}

/** The only provider EverySync ships: personal E2EE vaults. */
object ProviderIds {
    const val E2EE_RELAY = "e2ee_relay"
}

/** In-memory baseline store for tests and drivers that manage their own persistence. */
class MemoryBaselineStore {
    private val snapshots = mutableMapOf<String, RelaySnapshot>()

    fun load(): Map<String, RelaySnapshot> = snapshots.toMap()
    fun save(list: List<RelaySnapshot>) {
        for (s in list) snapshots[s.syncId] = s
    }
    fun remove(ids: List<String>) {
        for (id in ids) snapshots.remove(id)
    }
}
