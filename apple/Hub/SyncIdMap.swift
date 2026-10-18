// EverySync Hub — syncId <-> system-identifier map (hub-local state).
//
// System stores (`EKReminder`, `CNContact`, `EKEvent`) own their identifiers
// (`calendarItemIdentifier`, `CNContact.identifier`, `eventIdentifier`), and
// those identifiers are NOT stable across devices, restores, or (for
// reminders) list moves. The relay sync identity (`uid`) is the stable key.
// This map bridges them — hub-local ONLY, never an IPC column, never
// user-visible, never synced.
//
// Also persists the KNOWN-syncId set per vault that tombstone detection needs
// (`TodoReminderSink` / `ContactSink` / `CalendarSink` detectLocalDeletes):
// after a HEALTHY fetch, known-minus-fetched = genuine local deletes.
//
// Storage: hub-local SQLite (`hub-syncids.sqlite` in the hub's Application
// Support dir — NOT the App-Group container; shells never need it).
// Schema v1:
//   `syncids(vault TEXT, kind TEXT, syncId TEXT, systemId TEXT,
//            PRIMARY KEY (vault, kind, syncId))`
//   `known(vault TEXT, kind TEXT, syncId TEXT,
//          PRIMARY KEY (vault, kind, syncId))`
// `kind` is one of `todo` / `contact` / `event` (matches `TodoKind` /
// `ContactKind` / `CalendarKind` `.kind`).

import Foundation
import SQLite3

public enum SyncIdMapError: Error {
    case supportDirUnavailable
    case sqlite(String)
}

public final class SyncIdMap {
    private let fileManager = FileManager.default
    private let queue = DispatchQueue(label: "com.vayunmathur.everysync.syncid-map")

    public init() {}

    // MARK: - Ops

    /// Records the system identifier for a syncId (upsert).
    public func setSystemId(vaultId: String, kind: String, syncId: String, systemId: String) throws {
        try queue.sync {
            try withDatabase { db in
                try exec(db: db, sql: """
                    INSERT INTO syncids(vault, kind, syncId, systemId)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT(vault, kind, syncId) DO UPDATE SET systemId = excluded.systemId;
                    """, bind: [vaultId, kind, syncId, systemId])
                try exec(db: db, sql: """
                    INSERT INTO known(vault, kind, syncId) VALUES (?, ?, ?)
                    ON CONFLICT(vault, kind, syncId) DO NOTHING;
                    """, bind: [vaultId, kind, syncId])
            }
        }
    }

    /// Looks up the system identifier for a syncId; nil when unknown.
    public func systemId(vaultId: String, kind: String, syncId: String) throws -> String? {
        try queue.sync {
            try withDatabase { db in
                var stmt: OpaquePointer?
                guard sqlite3_prepare_v2(db, "SELECT systemId FROM syncids WHERE vault = ? AND kind = ? AND syncId = ?;", -1, &stmt, nil) == SQLITE_OK, let stmt else {
                    throw SyncIdMapError.sqlite("prepare failed")
                }
                defer { sqlite3_finalize(stmt) }
                sqlite3_bind_text(stmt, 1, vaultId, -1, SQLITE_TRANSIENT_MAP)
                sqlite3_bind_text(stmt, 2, kind, -1, SQLITE_TRANSIENT_MAP)
                sqlite3_bind_text(stmt, 3, syncId, -1, SQLITE_TRANSIENT_MAP)
                guard sqlite3_step(stmt) == SQLITE_ROW,
                      let c = sqlite3_column_text(stmt, 0) else { return nil }
                return String(cString: c)
            }
        }
    }

    /// All known syncIds for a vault+kind (tombstone detection baseline).
    public func knownSyncIds(vaultId: String, kind: String) throws -> Set<String> {
        try queue.sync {
            try withDatabase { db in
                var stmt: OpaquePointer?
                guard sqlite3_prepare_v2(db, "SELECT syncId FROM known WHERE vault = ? AND kind = ?;", -1, &stmt, nil) == SQLITE_OK, let stmt else {
                    throw SyncIdMapError.sqlite("prepare failed")
                }
                defer { sqlite3_finalize(stmt) }
                sqlite3_bind_text(stmt, 1, vaultId, -1, SQLITE_TRANSIENT_MAP)
                sqlite3_bind_text(stmt, 2, kind, -1, SQLITE_TRANSIENT_MAP)
                var out = Set<String>()
                while sqlite3_step(stmt) == SQLITE_ROW {
                    if let c = sqlite3_column_text(stmt, 0) { out.insert(String(cString: c)) }
                }
                return out
            }
        }
    }

    /// Drops a syncId from both tables (tombstone acknowledged / purged).
    public func forget(vaultId: String, kind: String, syncId: String) throws {
        try queue.sync {
            try withDatabase { db in
                try exec(db: db, sql: "DELETE FROM syncids WHERE vault = ? AND kind = ? AND syncId = ?;", bind: [vaultId, kind, syncId])
                try exec(db: db, sql: "DELETE FROM known WHERE vault = ? AND kind = ? AND syncId = ?;", bind: [vaultId, kind, syncId])
            }
        }
    }

    // MARK: - SQLite

    private func databaseURL() throws -> URL {
        let base = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
        guard let base else { throw SyncIdMapError.supportDirUnavailable }
        let dir = base.appendingPathComponent("com.vayunmathur.everysync.hub", isDirectory: true)
        try fileManager.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("hub-syncids.sqlite")
    }

    private func withDatabase<T>(_ body: (OpaquePointer) throws -> T) throws -> T {
        var db: OpaquePointer?
        guard sqlite3_open_v2(try databaseURL().path, &db, SQLITE_OPEN_CREATE | SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX, nil) == SQLITE_OK, let db else {
            throw SyncIdMapError.sqlite("open failed")
        }
        defer { sqlite3_close(db) }
        // `journal_mode` returns a row — step it query-style, not via exec().
        var pragma: OpaquePointer?
        guard sqlite3_prepare_v2(db, "PRAGMA journal_mode=WAL;", -1, &pragma, nil) == SQLITE_OK, let pragma else {
            throw SyncIdMapError.sqlite("pragma failed")
        }
        defer { sqlite3_finalize(pragma) }
        while sqlite3_step(pragma) == SQLITE_ROW {}
        try exec(db: db, sql: """
            CREATE TABLE IF NOT EXISTS syncids(
              vault TEXT NOT NULL, kind TEXT NOT NULL, syncId TEXT NOT NULL,
              systemId TEXT NOT NULL, PRIMARY KEY (vault, kind, syncId));
            """, bind: [])
        try exec(db: db, sql: """
            CREATE TABLE IF NOT EXISTS known(
              vault TEXT NOT NULL, kind TEXT NOT NULL, syncId TEXT NOT NULL,
              PRIMARY KEY (vault, kind, syncId));
            """, bind: [])
        return try body(db)
    }

    @discardableResult
    private func exec(db: OpaquePointer, sql: String, bind: [String]) throws -> Int {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else {
            throw SyncIdMapError.sqlite(String(cString: sqlite3_errmsg(db)))
        }
        defer { sqlite3_finalize(stmt) }
        for (i, value) in bind.enumerated() {
            sqlite3_bind_text(stmt, Int32(i + 1), value, -1, SQLITE_TRANSIENT_MAP)
        }
        guard sqlite3_step(stmt) == SQLITE_DONE else {
            throw SyncIdMapError.sqlite(String(cString: sqlite3_errmsg(db)))
        }
        return Int(sqlite3_changes(db))
    }
}

private let SQLITE_TRANSIENT_MAP = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
