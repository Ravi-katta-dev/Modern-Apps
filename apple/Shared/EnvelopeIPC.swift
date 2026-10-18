// EverySync Apple shells — shared envelope IPC store.
//
// Zero sync logic in Swift. This file implements ONLY the three IPC ops from
// `apple/IPC_CONTRACT.md` over the App-Group shared container, with identical
// table / op / column semantics to the KMP `SyncSink` interface
// (`querySince` / `upsertRaw` / `deleteByVault`; columns
// `syncId / updatedAt / deleted / payloadJson / contentHash`).
//
// The hub is the only process that talks to the relay. Shells only touch this
// store; the XCFramework (merge / transport / crypto) reads the same SQLite
// files on the Kotlin side (see ../XCFramework/IOS_ACTUALS_NOTES.md).
//
// Loss-prevention rules honored here:
// - A failed local read THROWS (never returns empty). Callers must abort the
//   cycle, exactly like `SyncSink.listLocalOk == false`.
// - Only explicit tombstones delete (soft-delete tables). Absence without a
//   tombstone resurrects — never deletes.
// - Passwords hard-delete; notes/todos soft-delete.
//
// MAC-CHECK (needs xcodebuild on a Mac):
// - `import SQLite3` resolves against libsqlite3.tbd (system library).
// - App-Group identifier below must use the real team prefix.

import Foundation
import SQLite3

/// App-Group shared container identifier. The `<team>` placeholder from
/// `IPC_CONTRACT.md` must be replaced with the real team prefix on the Mac
/// (Xcode signing will fail otherwise). Single constant so all targets agree.
public enum EverysyncGroup {
    public static let identifier = "group.com.vayunmathur.everysync.shared"
}

/// One envelope row. Column semantics are identical to the KMP `SyncSink`
/// columns (`EntityKind.syncId` -> `RemoteX.uid`, `RemoteX.updatedAt`,
/// `RemoteX.deleted`, `PasswordKind`/`NoteKind`/`TodoKind.toJson`,
/// `EntityKind.contentHash`).
public struct EnvelopeRow: Equatable {
    /// Relay sync identity (opaque 32-char id).
    public let syncId: String
    /// Last modification, epoch ms; drives relay LWW.
    public let updatedAt: Int64
    /// Tombstone flag. Included in `querySince` results for soft-delete types.
    public let deleted: Bool
    /// Full `RemoteX` JSON envelope. Swift treats this as OPAQUE bytes:
    /// produced/consumed only via the XCFramework (`*Kind.toJson/fromJson`).
    /// Swift never parses, merges, hashes, or encrypts it.
    public let payloadJson: String
    /// `relayContentHash(canonical + "\0deleted=…")`, computed in the
    /// XCFramework (`EntityKind.contentHash`). Swift never computes this.
    public let contentHash: String

    public init(syncId: String, updatedAt: Int64, deleted: Bool, payloadJson: String, contentHash: String) {
        self.syncId = syncId
        self.updatedAt = updatedAt
        self.deleted = deleted
        self.payloadJson = payloadJson
        self.contentHash = contentHash
    }
}

/// IPC tables. Names map 1:1 to relay channels (see IPC_CONTRACT.md).
public enum EnvelopeTable: String, CaseIterable {
    case passwords  // `passwords:<vaultId>` — HARD delete
    case notes      // `notes:<vaultId>`     — SOFT delete (tombstones persist)
    case todos      // `todos:<vaultId>`     — SOFT delete (tombstones persist)
    case contacts   // hub-internal; same columns
    case calendars  // hub-internal; same columns

    var isHardDelete: Bool { self == .passwords }
}

public enum EnvelopeIPCError: Error {
    case containerUnavailable
    case sqlite(String)
    case unhealthyRead(String)
}

/// Thread-safe, `NSFileCoordinator`-guarded SQLite envelope store.
///
/// - Location: `<group>/vault/<vaultId>/envelopes.sqlite` (one file per vault)
///   plus a `blobs/` sidecar dir (see `BlobStore.swift`).
/// - Mode: WAL + `busy_timeout=5000` so the Kotlin `RelayStore` shims (core
///   owner, same schema — see `../XCFramework/IOS_ACTUALS_NOTES.md`) can hold
///   the same files open concurrently. All Swift access goes through
///   `NSFileCoordinator`; the Kotlin side relies on SQLite locking + WAL.
/// - Schema version: v1 (see `../IPC_CONTRACT.md` appendix). Any column/table
///   change must update the contract doc AND the Kotlin shim spec together.
public final class EnvelopeIPC {
    private let fileManager = FileManager.default
    private let coordinator = NSFileCoordinator()
    private let queue = DispatchQueue(label: "com.vayunmathur.everysync.envelope-ipc")

    public init() {}

    // MARK: - Ops (map 1:1 to KMP `SyncSink`)

    /// `querySince` — rows with `updatedAt > since`, tombstones included.
    /// Drives both baseline reads and delta detection. THROWS on any unhealthy
    /// read; callers must abort (never fold failure as empty).
    public func querySince(vaultId: String, table: EnvelopeTable, since: Int64) throws -> [EnvelopeRow] {
        try queue.sync {
            let url = try databaseURL(vaultId: vaultId)
            var rows: [EnvelopeRow] = []
            var caught: Error?
            try coordinateReading(url: url) {
                do {
                    rows = try self.withDatabase(at: url) { db in
                        try self.query(db: db, table: table, since: since)
                    }
                } catch {
                    caught = error
                }
            }
            if let caught { throw caught }
            return rows
        }
    }

    /// `upsertRaw` — idempotent upsert keyed on `syncId`; preserves pulled
    /// timestamps (no timestamp rewriting here — `updatedAt` is stored verbatim).
    public func upsertRaw(vaultId: String, table: EnvelopeTable, rows: [EnvelopeRow]) throws {
        guard !rows.isEmpty else { return }
        try queue.sync {
            let url = try databaseURL(vaultId: vaultId)
            var caught: Error?
            try coordinateWriting(url: url) {
                do {
                    try self.withDatabase(at: url) { db in
                        try self.upsert(db: db, table: table, rows: rows)
                    }
                } catch {
                    caught = error
                }
            }
            if let caught { throw caught }
        }
    }

    /// `deleteByVault` — applies a pulled relay tombstone:
    /// - `passwords`: HARD-delete the row (rows vanish; only the app-side
    ///   snapshot remembers them).
    /// - `notes`/`todos`/`contacts`/`calendars`: set `deleted=1` tombstone.
    ///   The tombstone envelope itself always arrives via `upsertRaw`; this op
    ///   is for shell-side deletes, which the hub folds into tombstones.
    /// Merge-driven deletes ALWAYS arrive as tombstone envelopes, never raw.
    ///
    /// - Parameter now: caller timestamp (epoch ms) stamped onto the tombstone
    ///   row so delta queries (`updatedAt > cursor`) observe the delete. The
    ///   merge overwrites it with the agreed stamp; the value here only needs
    ///   to be newer than the last cursor.
    public func deleteByVault(vaultId: String, table: EnvelopeTable, syncId: String, now: Int64) throws {
        try queue.sync {
            let url = try databaseURL(vaultId: vaultId)
            var caught: Error?
            try coordinateWriting(url: url) {
                do {
                    try self.withDatabase(at: url) { db in
                        if table.isHardDelete {
                            try self.exec(db: db, sql: "DELETE FROM \(table.sql) WHERE syncId = ?;", bind: [syncId])
                        } else {
                            // Tombstone the row; keep payloadJson + contentHash untouched —
                            // the hub replaces them with the tombstone envelope on merge.
                            // Stamp `now` so delta queries (updatedAt > cursor) observe
                            // the delete; the merge overwrites it with the agreed stamp.
                            try self.execTombstone(db: db, table: table, syncId: syncId, now: now)
                        }
                    }
                } catch {
                    caught = error
                }
            }
            if let caught { throw caught }
        }
    }

    /// Removes every row for this vault (purge keeps the vault itself).
    /// Used for account removal / re-provision; the next healthy pull
    /// re-enables repopulation (same as the relay conformance checklist).
    public func purgeVault(vaultId: String) throws {
        try queue.sync {
            let url = try databaseURL(vaultId: vaultId)
            var caught: Error?
            try coordinateWriting(url: url) {
                do {
                    try self.withDatabase(at: url) { db in
                        for table in EnvelopeTable.allCases {
                            try self.exec(db: db, sql: "DELETE FROM \(table.sql);", bind: [])
                        }
                    }
                } catch {
                    caught = error
                }
            }
            if let caught { throw caught }
        }
    }

    // MARK: - Paths

    func containerURL() throws -> URL {
        guard let url = fileManager.containerURL(forSecurityApplicationGroupIdentifier: EverysyncGroup.identifier) else {
            throw EnvelopeIPCError.containerUnavailable
        }
        return url
    }

    func vaultDirectory(vaultId: String) throws -> URL {
        precondition(!vaultId.isEmpty && !vaultId.contains("/"), "vaultId must be a bare id")
        return try containerURL().appendingPathComponent("vault/\(vaultId)", isDirectory: true)
    }

    func databaseURL(vaultId: String) throws -> URL {
        let dir = try vaultDirectory(vaultId: vaultId)
        try fileManager.createDirectory(at: dir, withIntermediateDirectories: true)
        try fileManager.createDirectory(at: dir.appendingPathComponent("blobs", isDirectory: true), withIntermediateDirectories: true)
        return dir.appendingPathComponent("envelopes.sqlite")
    }

    // MARK: - SQLite

    private func withDatabase<T>(at url: URL, _ body: (OpaquePointer) throws -> T) throws -> T {
        var db: OpaquePointer?
        let flags = SQLITE_OPEN_CREATE | SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX
        guard sqlite3_open_v2(url.path, &db, flags, nil) == SQLITE_OK, let db else {
            throw EnvelopeIPCError.sqlite("open failed: \(url.path)")
        }
        defer { sqlite3_close(db) }
        // NOTE: `PRAGMA journal_mode=WAL` returns a result row (SQLITE_ROW),
        // so it needs a query-style step — plain exec() would throw.
        try queryPragma(db: db, sql: "PRAGMA journal_mode=WAL;")
        try exec(db: db, sql: "PRAGMA busy_timeout=5000;", bind: [])
        for table in EnvelopeTable.allCases {
            try exec(db: db, sql: """
                CREATE TABLE IF NOT EXISTS \(table.sql) (
                  syncId TEXT PRIMARY KEY,
                  updatedAt INTEGER NOT NULL,
                  deleted INTEGER NOT NULL DEFAULT 0,
                  payloadJson TEXT NOT NULL,
                  contentHash TEXT NOT NULL
                );
                """, bind: [])
            try exec(db: db, sql: "CREATE INDEX IF NOT EXISTS idx_\(table.sql)_updatedAt ON \(table.sql)(updatedAt);", bind: [])
        }
        return try body(db)
    }

    private func query(db: OpaquePointer, table: EnvelopeTable, since: Int64) throws -> [EnvelopeRow] {
        let sql = "SELECT syncId, updatedAt, deleted, payloadJson, contentHash FROM \(table.sql) WHERE updatedAt > ? ORDER BY updatedAt;"
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else {
            throw EnvelopeIPCError.sqlite(String(cString: sqlite3_errmsg(db)))
        }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_int64(stmt, 1, since)
        var rows: [EnvelopeRow] = []
        while true {
            let step = sqlite3_step(stmt)
            if step == SQLITE_ROW {
                guard let syncIdC = sqlite3_column_text(stmt, 0),
                      let payloadC = sqlite3_column_text(stmt, 3),
                      let hashC = sqlite3_column_text(stmt, 4) else {
                    throw EnvelopeIPCError.unhealthyRead("null column in \(table.rawValue)")
                }
                rows.append(EnvelopeRow(
                    syncId: String(cString: syncIdC),
                    updatedAt: sqlite3_column_int64(stmt, 1),
                    deleted: sqlite3_column_int(stmt, 2) != 0,
                    payloadJson: String(cString: payloadC),
                    contentHash: String(cString: hashC)
                ))
            } else if step == SQLITE_DONE {
                break
            } else {
                throw EnvelopeIPCError.unhealthyRead(String(cString: sqlite3_errmsg(db)))
            }
        }
        return rows
    }

    /// Idempotent upsert keyed on `syncId`; preserves pulled timestamps
    /// (stores `updatedAt` verbatim — no rewriting). Batched in one
    /// transaction.
    private func upsert(db: OpaquePointer, table: EnvelopeTable, rows: [EnvelopeRow]) throws {
        try exec(db: db, sql: "BEGIN IMMEDIATE;", bind: [])
        do {
            for row in rows {
                try upsertOne(db: db, table: table, row: row)
            }
            try exec(db: db, sql: "COMMIT;", bind: [])
        } catch {
            try? exec(db: db, sql: "ROLLBACK;", bind: [])
            throw error
        }
    }

    /// Single-row upsert. Factored out (not inlined in the loop) so the
    /// statement `defer`-finalize runs per row — `defer` inside a `for` body
    /// would pile up until function return and leak statements.
    private func upsertOne(db: OpaquePointer, table: EnvelopeTable, row: EnvelopeRow) throws {
        var stmt: OpaquePointer?
        let sql = "INSERT INTO \(table.sql)(syncId, updatedAt, deleted, payloadJson, contentHash) VALUES (?, ?, ?, ?, ?) ON CONFLICT(syncId) DO UPDATE SET updatedAt = excluded.updatedAt, deleted = excluded.deleted, payloadJson = excluded.payloadJson, contentHash = excluded.contentHash;"
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else {
            throw EnvelopeIPCError.sqlite(String(cString: sqlite3_errmsg(db)))
        }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_text(stmt, 1, row.syncId, -1, SQLITE_TRANSIENT)
        sqlite3_bind_int64(stmt, 2, row.updatedAt)
        sqlite3_bind_int(stmt, 3, row.deleted ? 1 : 0)
        sqlite3_bind_text(stmt, 4, row.payloadJson, -1, SQLITE_TRANSIENT)
        sqlite3_bind_text(stmt, 5, row.contentHash, -1, SQLITE_TRANSIENT)
        guard sqlite3_step(stmt) == SQLITE_DONE else {
            throw EnvelopeIPCError.sqlite(String(cString: sqlite3_errmsg(db)))
        }
    }

    @discardableResult
    private func exec(db: OpaquePointer, sql: String, bind: [String]) throws -> Int {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else {
            throw EnvelopeIPCError.sqlite(String(cString: sqlite3_errmsg(db)))
        }
        defer { sqlite3_finalize(stmt) }
        for (i, value) in bind.enumerated() {
            sqlite3_bind_text(stmt, Int32(i + 1), value, -1, SQLITE_TRANSIENT)
        }
        guard sqlite3_step(stmt) == SQLITE_DONE else {
            throw EnvelopeIPCError.sqlite(String(cString: sqlite3_errmsg(db)))
        }
        return Int(sqlite3_changes(db))
    }

    /// Tombstone update with an int64 timestamp bind (soft-delete tables).
    private func execTombstone(db: OpaquePointer, table: EnvelopeTable, syncId: String, now: Int64) throws {
        let sql = "UPDATE \(table.sql) SET deleted = 1, updatedAt = ? WHERE syncId = ?;"
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else {
            throw EnvelopeIPCError.sqlite(String(cString: sqlite3_errmsg(db)))
        }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_int64(stmt, 1, now)
        sqlite3_bind_text(stmt, 2, syncId, -1, SQLITE_TRANSIENT)
        guard sqlite3_step(stmt) == SQLITE_DONE else {
            throw EnvelopeIPCError.sqlite(String(cString: sqlite3_errmsg(db)))
        }
    }

    /// Steps a row-returning PRAGMA to completion (e.g. `journal_mode`).
    /// Any step result other than ROW/DONE is an unhealthy read.
    private func queryPragma(db: OpaquePointer, sql: String) throws {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else {
            throw EnvelopeIPCError.sqlite(String(cString: sqlite3_errmsg(db)))
        }
        defer { sqlite3_finalize(stmt) }
        while true {
            switch sqlite3_step(stmt) {
            case SQLITE_ROW: continue
            case SQLITE_DONE: return
            default: throw EnvelopeIPCError.unhealthyRead(String(cString: sqlite3_errmsg(db)))
            }
        }
    }

    // MARK: - Coordination

    private func coordinateReading(url: URL, _ body: () -> Void) throws {
        var error: NSError?
        coordinator.coordinate(readingItemAt: url, options: [], error: &error, byAccessor: { _ in body() })
        // A coordination failure is an unhealthy read — NEVER fold as empty.
        if let error {
            throw EnvelopeIPCError.unhealthyRead("NSFileCoordinator read failed: \(error)")
        }
    }

    private func coordinateWriting(url: URL, _ body: () -> Void) throws {
        var error: NSError?
        coordinator.coordinate(writingItemAt: url, options: [], error: &error, byAccessor: { _ in body() })
        if let error {
            throw EnvelopeIPCError.unhealthyRead("NSFileCoordinator write failed: \(error)")
        }
    }
}

private extension EnvelopeTable {
    /// Allowlisted table names — never interpolate raw strings into SQL.
    var sql: String { rawValue }
}

// SQLITE_TRANSIENT helper (macro, not imported from the module map).
private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
