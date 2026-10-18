package com.vayunmathur.desktop.notes.systemsinks.linux

import com.vayunmathur.everysync.core.data.RemoteContact
import com.vayunmathur.everysync.core.platform.LocalChange
import com.vayunmathur.everysync.core.sinks.ContactsSystemSink
import com.vayunmathur.everysync.core.sync.LocalReadException
import com.vayunmathur.everysync.core.sync.RelaySnapshot
import com.vayunmathur.everysync.core.sync.RelayStore
import com.vayunmathur.everysync.core.sync.adapters.ContactKind
import com.vayunmathur.desktop.notes.systemsinks.FileStoreIo
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Linux `ContactsSystemSink` over Evolution Data Server (`libebook`): syncs
 * the `contacts:<vaultId>` relay channel into the user's EDS address book.
 *
 * **BACKEND: file mirror (EDS native path is a stub).** [EdsBridge.isAvailable]
 * is false wherever no `libebook-2.0` links (CI, Windows, bare containers), so
 * this sink persists the channel to `contacts-<vaultId>.json` under [dataDir]
 * with the EXACT EDS field mapping below. Swapping in the native client
 * changes storage, never semantics: the merge contract (tombstone-only delete,
 * pulled-`updatedAt` preserved verbatim, identity = relay uid) is identical on
 * both backends.
 *
 * EDS field mapping (what the native path MUST persist per row):
 * - Sync identity: `EContact` `UID` property = relay `uid` (created via
 *   `e_book_client_add_contact_sync`, which returns the server uid — the sink
 *   writes the relay uid in, matching Android `RawContacts.SOURCE_ID` usage).
 * - Stamp: `EContact` `REV` (revision timestamp) = relay `updatedAt` (ms) —
 *   preserved verbatim on relay→EDS writes; read back on EDS→merge reads.
 *   Mirrors Android `SYNC1`.
 * - Vault tag: `X-EVERYSYNC-VAULT: <vaultId>` extended vCard property on every
 *   contact this sink creates (mirrors Android `SYNC2`), so a shared address
 *   book holding several vaults stays partitionable.
 * - Body: the same vCard text `core.format.VCard.serialize` emits — N/FN/ORG/
 *   TEL/EMAIL/ADR/BDAY/NOTE with the shared `PhoneKind`/`EmailKind`/`PostalKind`
 *   ints riding `TYPE=` params (`CELL/WORK/HOME/FAX/VOICE`,
 *   `WORK/HOME/INTERNET`, `WORK/HOME/OTHER`). `ContactKind.canonical` strips
 *   uid/etag/href only for hashing — storage keeps the full row.
 * - Query: `e_book_client_get_contacts_sync` with sexp `"#t"` filtered to
 *   contacts carrying this vault's `X-EVERYSYNC-VAULT`.
 *
 * Soft-delete rule (provider `DELETED`-flag semantics): delete paths NEVER
 * hard-remove a live row. `delete()` flags the mirror row (and, natively,
 * would set the EDS-side deleted mark) so the tombstone survives in
 * [listLocal] until the merge proves both sides acknowledged it. Only
 * [RelayStore.deleteLocal] hard-removes, for merge-acknowledged ids only.
 * Absence without a tombstone is a missed read, never a delete (matches
 * `EntityKind.localDeletesAreAuthoritative = true` for contacts).
 */
class EdsContactsSink(
    private val dataDir: File,
    private val vaultId: String,
) : ContactsSystemSink, RelayStore<RemoteContact> {

    private val storeFile: File get() = File(dataDir, "contacts-$vaultId.json")
    private val snapshotFile: File get() = File(dataDir, "snapshots-contact-$vaultId.tsv")
    private val rows = LinkedHashMap<String, RemoteContact>()
    private val snapshots = LinkedHashMap<String, RelaySnapshot>()
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var lastReadOk: Boolean = true

    /** True on a Linux host with a linkable `libebook-2.0` (see [EdsBridge]). */
    val nativeAvailable: Boolean get() = EdsBridge.isAvailable()

    /**
     * Full local state, tombstones included. A failed read throws
     * [LocalReadException] (never an empty list): merging a failure as empty
     * would delete every baseline-present relay record.
     */
    override suspend fun listLocal(vaultId: String): List<RemoteContact> = mutex.withLock {
        loadLocked()
        lastReadOk = true
        rows.values.toList()
    }

    override val listLocalOk: Boolean get() = lastReadOk

    /** Rows with `updatedAt` past [since], tombstones included. */
    override suspend fun localChanges(vaultId: String, since: Long): List<LocalChange<RemoteContact>> =
        mutex.withLock {
            loadLocked()
            rows.values.filter { it.updatedAt > since }.map {
                LocalChange(it.uid, it.updatedAt, it.deleted, it)
            }
        }

    /**
     * Idempotent upsert keyed on the relay sync identity, preserving the
     * pulled `updatedAt`. `deleted = true` flags the row instead of removing it.
     */
    override suspend fun upsert(vaultId: String, item: RemoteContact) {
        if (item.uid.isBlank()) return
        mutex.withLock {
            loadLocked()
            rows[item.uid] = item
            saveLocked()
        }
    }

    /**
     * Soft-delete: flags the row (EDS-side: contact deleted mark). Pulled
     * tombstones arrive via [upsert] as `deleted = true` envelopes, never as
     * raw deletes.
     */
    override suspend fun delete(vaultId: String, syncId: String) {
        mutex.withLock {
            loadLocked()
            val existing = rows[syncId] ?: return
            rows[syncId] = ContactKind.withUpdatedAt(
                ContactKind.withDeleted(existing, true),
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

    override suspend fun listLocal(): List<RemoteContact> = listLocal(vaultId)

    override suspend fun upsertLocal(items: List<RemoteContact>) {
        for (item in items) upsert(vaultId, item)
    }

    /**
     * Merge-confirmed removals only: every id here is baseline-proved
     * acknowledged on both sides.
     */
    override suspend fun deleteLocal(items: List<RemoteContact>) {
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

    override fun parseRemote(json: String): RemoteContact? =
        ContactKind.fromJson(json)?.takeIf { it.uid.isNotBlank() }

    override fun serializeForRemote(entity: RemoteContact): String = ContactKind.toJson(entity)

    // --- File mirror persistence (JSON rows; snapshots via FileStoreIo) ---

    private fun loadLocked() {
        val file = storeFile
        if (!file.isFile) return
        try {
            val decoded = json.decodeFromString<List<RemoteContact>>(file.readText())
            rows.clear()
            for (contact in decoded) {
                if (contact.uid.isNotBlank()) rows[contact.uid] = contact
            }
            lastReadOk = true
        } catch (_: Exception) {
            lastReadOk = false
            throw LocalReadException("EdsContactsSink read failed for $vaultId")
        }
    }

    private fun saveLocked() {
        dataDir.mkdirs()
        storeFile.writeText(json.encodeToString(rows.values.toList()))
    }
}
