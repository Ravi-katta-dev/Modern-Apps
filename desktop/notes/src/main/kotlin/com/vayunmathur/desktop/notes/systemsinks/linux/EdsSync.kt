package com.vayunmathur.desktop.notes.systemsinks.linux

import com.vayunmathur.everysync.core.network.RelayTransport
import com.vayunmathur.everysync.core.network.appendRecords
import com.vayunmathur.everysync.core.network.pullRecords
import com.vayunmathur.everysync.core.network.recordChannel
import com.vayunmathur.everysync.core.provider.SyncDriver
import com.vayunmathur.everysync.core.sync.EntityKind
import com.vayunmathur.everysync.core.sync.RelayStore
import com.vayunmathur.everysync.core.sync.adapters.CalendarKind
import com.vayunmathur.everysync.core.sync.adapters.ContactKind
import com.vayunmathur.everysync.core.sync.adapters.TodoKind
import java.io.File

/**
 * Drives the calendar + contacts + EDS-todos channels through the shared
 * [SyncDriver] over the shared [RelayTransport] (JVM:
 * `JvmRelayTransport`).
 *
 * Per-channel cycle (mirrors `E2eeProvider.driveMerge` and
 * `NoteSync.syncChannel`):
 *  1. Fold the FULL decrypted record log from 0 (tombstones included — full
 *     state, never a delta, so absence vs deletion stays decidable).
 *  2. A failed pull (null) or a fully-undecryptable log aborts the channel
 *     with zero writes — never merge a failure as empty.
 *  3. [SyncDriver.drive] merges against the sink's full local state +
 *     baseline, appends `remoteOut` BEFORE persisting locally, then applies
 *     the local half.
 *
 * Channels: `calendar:<vaultId>`, `contacts:<vaultId>`,
 * `todos:<vaultId>` (via [TodoKind.channel], same channel the [TodoView]
 * shell syncs — one channel, two bindings).
 *
 * Vault-key note: the caller supplies the epoch-0 vault key directly. Roster
 * / key-epoch / ownership resolution (`VaultRoster`, `VaultKeys`,
 * `OwnerTransfer`) is future work for the desktop shell — shared vaults that
 * rotated keys need that before this driver can read them.
 */
class EdsSync(
    private val transport: RelayTransport,
    private val dataDir: File,
) {
    data class ChannelResult(val ok: Boolean, val localCount: Int)
    data class SyncResult(
        val calendar: ChannelResult,
        val contacts: ChannelResult,
        val todos: ChannelResult,
    )

    suspend fun syncOnce(vaultId: String, vaultKey: ByteArray): SyncResult {
        val calendar = EdsCalendarSink(dataDir, vaultId)
        val contacts = EdsContactsSink(dataDir, vaultId)
        val todos = EdsTodoSink(dataDir, vaultId)
        val now = System.currentTimeMillis()
        val calendarOk = syncChannel(
            CalendarKind, calendar, recordChannel("calendar", vaultId), vaultKey, now,
        )
        val contactsOk = syncChannel(
            ContactKind, contacts, recordChannel("contacts", vaultId), vaultKey, now,
        )
        val todosOk = syncChannel(
            TodoKind, todos, TodoKind.channel(vaultId), vaultKey, now,
        )
        return SyncResult(
            calendar = ChannelResult(calendarOk, calendar.listLocal().size),
            contacts = ChannelResult(contactsOk, contacts.listLocal().size),
            todos = ChannelResult(todosOk, todos.listLocal().size),
        )
    }

    private suspend fun <T> syncChannel(
        kind: EntityKind<T>,
        store: RelayStore<T>,
        channel: String,
        key: ByteArray,
        now: Long,
    ): Boolean {
        val pulled = transport.pullRecords(channel, key, 0) ?: return false
        if (pulled.fullyUndecryptable) return false
        // Fold to FULL state: last envelope per sync id wins (append-only
        // log). Without this, a superseded envelope (e.g. a live row followed
        // by its tombstone) reaches the merge as a duplicate id and the
        // tombstone is adopted under a fresh id instead of reconciling.
        val folded = LinkedHashMap<String, String>()
        for (raw in pulled.items) {
            val entity = store.parseRemote(raw) ?: continue
            folded[kind.syncId(entity)] = raw
        }
        val remote = folded.values.mapNotNull { store.parseRemote(it) }
        return SyncDriver.drive(
            kind = kind,
            store = store,
            remoteEntities = remote,
            appendRemote = { items -> transport.appendRecords(channel, key, items) != null },
            now = now,
        )
    }
}
