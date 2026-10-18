// EverySync Hub — `RelayStore` shim over the envelope IPC.
//
// The XCFramework's `SyncDriver.drive(kind, store, remote, append, now)`
// needs a `RelayStore<T>` (`listLocal / upsertLocal / deleteLocal /
// loadBaseline / saveSnapshots / removeSnapshots / listLocalOk / parseRemote /
// serializeForRemote`). On Apple the envelopes live in the App-Group SQLite
// IPC store (shared with the Passwords/Notes shells), so this file documents
// the exact adapter the Mac wires in Kotlin (see
// `../XCFramework/IOS_ACTUALS_NOTES.md` for the Kotlin owner):
//
// - `listLocal(vaultId)` = `EnvelopeIPC.querySince(vaultId, table, since: -1)`
//   mapped through `*Kind.fromJson(payloadJson)`. ANY throw (SQLite error,
//   coordination failure) = unhealthy read: the shim reports
//   `listLocalOk == false` (or throws `LocalReadException`) so the driver
//   ABORTS — never folds failure as empty (invariant 1+2, verbatim).
//   Precedence: the ROW's `deleted` column wins over a stale `payloadJson`
//   flag. A shell-side delete (`deleteByVault`) tombstones the row and stamps
//   `updatedAt` but leaves the old payload bytes in place until the merge
//   writes the tombstone envelope — so the shim MUST treat
//   `row.deleted == true` as deleted regardless of what the payload says.
//   (Same reason `querySince` includes tombstones: absence without a
//   tombstone resurrects — never deletes.)
// - `parseRemote` = `*Kind.fromJson`; null skips a foreign/corrupt entry.
// - `serializeForRemote` = `*Kind.toJson` (decrypted form — encryption
//   happens in the transport's `appendRecords`, never here).
// - `upsertLocal`/`deleteLocal` = `upsertRaw` / per-table delete semantics
//   (passwords hard-delete, everything else tombstones — Swift holds no
//   opinion beyond applying driver output).
// - Baselines (`RelaySnapshot`: syncId/kind/contentHash/localUpdatedAt/
//   remoteModified/lastSyncedAt/deleted) persist in a hub-side sidecar
//   SQLite table (NOT in the IPC tables — the IPC columns are exactly the
//   five `SyncSink` columns, no more).
//
// Swift never implements this interface directly (generics over Kotlin types
// cross the boundary poorly); the live shim is Kotlin. This file exists so
// the mapping is specified once, in the repo, next to the sinks.

import Foundation
import EverysyncCore

/// Which IPC table backs each vault type in the shim.
public enum RelayStoreTables {
    /// `passwords:<vault>` <-> `passwords` table (HARD delete).
    public static let passwords = EnvelopeTable.passwords
    /// `notes:<vault>` <-> `notes` table (SOFT delete).
    public static let notes = EnvelopeTable.notes
    /// `todos:<vault>` <-> `todos` table (SOFT delete).
    /// `notes` + `createdAt` MUST be persisted (envelope + hash inputs).
    public static let todos = EnvelopeTable.todos
    /// Hub-internal contacts/calendar rows: same five columns; delete
    /// semantics per the XCFramework entity kind (Swift holds no opinion).
    public static let contacts = EnvelopeTable.contacts
    public static let calendars = EnvelopeTable.calendars
}

/// Baseline sidecar: `RelaySnapshot` rows persisted per (vault, kind).
/// Schema (hub-local SQLite, NOT the IPC store):
/// `baselines(syncId TEXT, kind TEXT, contentHash TEXT, localUpdatedAt
/// INTEGER, remoteModified INTEGER, lastSyncedAt INTEGER, deleted INTEGER,
/// PRIMARY KEY (syncId, kind))`.
public enum BaselineSidecar {
    public static let tableDDL = """
        CREATE TABLE IF NOT EXISTS baselines (
          syncId TEXT NOT NULL,
          kind TEXT NOT NULL,
          contentHash TEXT NOT NULL,
          localUpdatedAt INTEGER NOT NULL,
          remoteModified INTEGER NOT NULL,
          lastSyncedAt INTEGER NOT NULL,
          deleted INTEGER NOT NULL DEFAULT 0,
          PRIMARY KEY (syncId, kind)
        );
        """
}
