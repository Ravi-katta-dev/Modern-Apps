package com.vayunmathur.desktop.notes.systemsinks.windows

import com.vayunmathur.everysync.core.network.RelayTransport
import com.vayunmathur.everysync.core.network.appendRecords
import com.vayunmathur.everysync.core.network.pullRecords
import com.vayunmathur.everysync.core.network.recordChannel
import com.vayunmathur.everysync.core.provider.SyncDriver
import com.vayunmathur.everysync.core.sync.EntityKind
import com.vayunmathur.everysync.core.sync.RelayStore
import com.vayunmathur.everysync.core.sync.adapters.CalendarKind
import com.vayunmathur.everysync.core.sync.adapters.ContactKind
import java.io.File

/**
 * Drives the Windows v1 read-only mirrors through the shared [SyncDriver].
 *
 * The mirrors' `localChanges` is always empty (read-only v1: no local edit
 * source), so the merge's local half is trivially "nothing to push" — the
 * cycle still pulls the FULL folded log and applies the relay→local half
 * (`upsertLocal`/`deleteLocal`), keeping the mirror and its baseline current.
 * `remoteOut` on this path only ever carries re-appended adoptions of
 * unidentified relay rows, never local edits; `appendRemote` still runs
 * BEFORE the local persist per the driver contract.
 *
 * Channels: `calendar:<vaultId>`, `contacts:<vaultId>`. Todos are NOT driven
 * here — Windows renders the SAME `todos:<vaultId>` channel inside the Notes
 * UI via the existing `TodoView` path (read-write through
 * `updateTodoFromNote`).
 *
 * Invariants (same as every driver): a failed pull (null) or a
 * fully-undecryptable log aborts the channel with zero writes — never merge
 * a failure as empty.
 */
class WindowsSync(
    private val transport: RelayTransport,
    private val dataDir: File,
) {
    data class ChannelResult(val ok: Boolean, val localCount: Int)
    data class SyncResult(
        val calendar: ChannelResult,
        val contacts: ChannelResult,
    )

    suspend fun syncOnce(vaultId: String, vaultKey: ByteArray): SyncResult {
        val calendar = WindowsCalendarMirror(dataDir, vaultId)
        val contacts = WindowsContactsMirror(dataDir, vaultId)
        val now = System.currentTimeMillis()
        val calendarOk = syncChannel(
            CalendarKind, calendar, recordChannel("calendar", vaultId), vaultKey, now,
        )
        val contactsOk = syncChannel(
            ContactKind, contacts, recordChannel("contacts", vaultId), vaultKey, now,
        )
        return SyncResult(
            calendar = ChannelResult(calendarOk, calendar.listLocal().size),
            contacts = ChannelResult(contactsOk, contacts.listLocal().size),
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
        // log) — see EdsSync.syncChannel for why the raw log must not reach
        // the merge unfolded.
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
