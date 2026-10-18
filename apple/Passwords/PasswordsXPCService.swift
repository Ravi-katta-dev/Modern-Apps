// EverySync Passwords — XPC envelope service.
//
// The autofill extension cannot assume direct store access, so the containing
// Passwords app exposes this XPC service with EXACTLY the three IPC ops from
// `../IPC_CONTRACT.md` (which map 1:1 to KMP `SyncSink`). The service does
// `NSFileCoordinator` internally; callers just call the three methods.
//
// Service name: `com.vayunmathur.everysync.envelope-ipc` (must match the
// extension's `NSXPCConnection` + the service's `Info.plist`).
// The extension NEVER touches the relay — only these three ops.
//
// MAC-CHECK (needs xcodebuild on a Mac):
// - XPC service target with the App-Group entitlement
//   (`group.<team>.everysync.shared`).
// - `NSXPCInterface` signatures below match on both sides (exported object +
//   remote object). `@objc` + `NSSecureCoding`-compatible row type required
//   at the boundary — hence `XPCEnvelopeRow` (plain data, no logic).

import Foundation

/// XPC-safe envelope row. Same five columns as `EnvelopeRow` / KMP `SyncSink`:
/// `syncId / updatedAt / deleted / payloadJson / contentHash`. Opaque payload
/// — the extension forwards it to the XCFramework; it never parses it.
@objc(XPCEnvelopeRow)
public final class XPCEnvelopeRow: NSObject, NSSecureCoding {
    public static var supportsSecureCoding: Bool { true }

    public let syncId: String
    public let updatedAt: Int64
    public let deleted: Bool
    public let payloadJson: String
    public let contentHash: String

    public init(syncId: String, updatedAt: Int64, deleted: Bool, payloadJson: String, contentHash: String) {
        self.syncId = syncId
        self.updatedAt = updatedAt
        self.deleted = deleted
        self.payloadJson = payloadJson
        self.contentHash = contentHash
    }

    public func encode(with coder: NSCoder) {
        coder.encode(syncId, forKey: "syncId")
        coder.encode(updatedAt, forKey: "updatedAt")
        coder.encode(deleted, forKey: "deleted")
        coder.encode(payloadJson, forKey: "payloadJson")
        coder.encode(contentHash, forKey: "contentHash")
    }

    public required init?(coder: NSCoder) {
        guard let syncId = coder.decodeObject(of: NSString.self, forKey: "syncId") as String?,
              let payloadJson = coder.decodeObject(of: NSString.self, forKey: "payloadJson") as String?,
              let contentHash = coder.decodeObject(of: NSString.self, forKey: "contentHash") as String? else {
            return nil
        }
        self.syncId = syncId
        self.updatedAt = coder.decodeInt64(forKey: "updatedAt")
        self.deleted = coder.decodeBool(forKey: "deleted")
        self.payloadJson = payloadJson
        self.contentHash = contentHash
    }
}

/// The three IPC ops. Table names cross the boundary as strings
/// (`passwords` / `notes` / `todos` / `contacts` / `calendars`); the service
/// validates against the `EnvelopeTable` allowlist before touching SQLite.
@objc(EnvelopeIPCServiceProtocol)
public protocol EnvelopeIPCServiceProtocol {
    /// `querySince` — rows with `updatedAt > since`, tombstones included.
    /// Reply: rows, or error (unhealthy read — caller aborts, never empties).
    func querySince(vaultId: String, table: String, since: Int64, reply: @escaping ([XPCEnvelopeRow]?, Error?) -> Void)
    /// `upsertRaw` — idempotent upsert keyed on `syncId`.
    func upsertRaw(vaultId: String, table: String, rows: [XPCEnvelopeRow], reply: @escaping (Error?) -> Void)
    /// `deleteByVault` — hard-delete (passwords) or timestamped tombstone
    /// (everything else). `now` is epoch ms, stamped so delta queries observe
    /// the delete (see `EnvelopeIPC.deleteByVault`).
    func deleteByVault(vaultId: String, table: String, syncId: String, now: Int64, reply: @escaping (Error?) -> Void)
}

/// Service implementation. Thin wrapper over `EnvelopeIPC` (see
/// `../Shared/EnvelopeIPC.swift`) — no logic beyond allowlist validation.
///
/// MAC-CHECK: `EnvelopeIPC`/`EnvelopeTable`/`EnvelopeRow` live in the Shared
/// sources — the XPC service target must compile them in (or import the
/// shared module) or this file won't resolve. Same for `EverysyncCore`.
@objc(EnvelopeIPCService)
public final class EnvelopeIPCService: NSObject, EnvelopeIPCServiceProtocol {
    private let store = EnvelopeIPC()

    private func table(named raw: String) -> EnvelopeTable? {
        EnvelopeTable(rawValue: raw)
    }

    public func querySince(vaultId: String, table: String, since: Int64, reply: @escaping ([XPCEnvelopeRow]?, Error?) -> Void) {
        guard let t = self.table(named: table) else {
            reply(nil, EnvelopeIPCError.sqlite("unknown table: \(table)"))
            return
        }
        do {
            let rows = try store.querySince(vaultId: vaultId, table: t, since: since)
            reply(rows.map { XPCEnvelopeRow(syncId: $0.syncId, updatedAt: $0.updatedAt, deleted: $0.deleted, payloadJson: $0.payloadJson, contentHash: $0.contentHash) }, nil)
        } catch {
            reply(nil, error)
        }
    }

    public func upsertRaw(vaultId: String, table: String, rows: [XPCEnvelopeRow], reply: @escaping (Error?) -> Void) {
        guard let t = self.table(named: table) else {
            reply(EnvelopeIPCError.sqlite("unknown table: \(table)"))
            return
        }
        do {
            try store.upsertRaw(vaultId: vaultId, table: t, rows: rows.map {
                EnvelopeRow(syncId: $0.syncId, updatedAt: $0.updatedAt, deleted: $0.deleted, payloadJson: $0.payloadJson, contentHash: $0.contentHash)
            })
            reply(nil)
        } catch {
            reply(error)
        }
    }

    public func deleteByVault(vaultId: String, table: String, syncId: String, now: Int64, reply: @escaping (Error?) -> Void) {
        guard let t = self.table(named: table) else {
            reply(EnvelopeIPCError.sqlite("unknown table: \(table)"))
            return
        }
        do {
            try store.deleteByVault(vaultId: vaultId, table: t, syncId: syncId, now: now)
            reply(nil)
        } catch {
            reply(error)
        }
    }
}
