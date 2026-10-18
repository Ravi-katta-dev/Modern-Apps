// EverySync Apple shells — Keychain-backed `SecureStore` notes.
//
// The KMP `SecureStore` interface (`platform/Platform.kt`: `getBytes /
// `setBytes(onlyIfAbsent)` / `delete` / `getString` / `setString` / `getLong` /
// `setLong`) is implemented in KOTLIN (`IOS_ACTUALS_NOTES.md`), not here.
// This file documents the exact Keychain contract the Kotlin actual must
// implement so all three apps + extensions agree on one layout.
//
// DO NOT re-implement `SecureStore` in Swift. Swift calls the XCFramework's
// `SecureStore` (backed by Keychain per this contract); vault AES keys never
// appear in Swift values, logs, or share links.
//
// Keychain layout contract (all entries `kSecClassGenericPassword`):
// - Service: `com.vayunmathur.everysync.securestore`
// - Account: the `SecureStore` key verbatim (e.g. `personalKemPriv`,
//   `relay_cursor:todos:<vault>:todos:<vault>`, `personalDeviceId`).
// - Value: `kSecValueData` = raw bytes (strings UTF-8, longs big-endian Int64).
// - Access group: `$(AppIdentifierPrefix)com.vayunmathur.everysync.shared`
//   so the main apps + Credential Provider + Share extension share one store.
// - Accessibility: `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`
//   (no iCloud sync — relay keys never leave the device except via
//   PQC-sealed inbox invites).
//
// Semantics mirror `MemorySecureStore` + the Android KeyStore actual:
// - `get*` returns nil when absent (absence is NOT an error).
// - `set*(onlyIfAbsent: true)` is atomic: check-then-add must hold the
//   Keychain entry (Kotlin serializes with a mutex, same as `MemorySecureStore`).
// - `delete` removes bytes+strings+longs rows for the name (single Keychain
//   item per name here, so one `SecItemDelete`).
// - `getLong` decodes 8-byte big-endian; malformed data returns nil (caller
//   treats as absent, same as the relay-cursor default 0).
//
// Key-hygiene invariant (verbatim): vault keys never user-visible. Share links
// carry ids only (`RelayLinks.shareLinkFor`); keys move exclusively via
// PQC-sealed inbox invites (`inbox:<deviceId>`). `RelayCursorStore` keys
// (`relay_cursor:<type>:<vault>:<channel>`) live here, NOT in the IPC tables.

import Foundation
import Security

/// Compile-time documentation of the Keychain layout. No instances: the live
/// implementation is the Kotlin `SecureStore` actual in the XCFramework.
public enum KeychainSecureStoreContract {
    public static let service = "com.vayunmathur.everysync.securestore"
    public static let accessGroup = "com.vayunmathur.everysync.shared"

    /// Keys the transport bootstrap writes (see `SharedRelayTransport.init`):
    /// PQC identity (`personalKemPub/Priv`, `personalDsaPub/Priv`) + stable
    /// device id (`personalDeviceId`). `onlyIfAbsent` on first write wins
    /// concurrent races — re-read after write, exactly like
    /// `PqcIdentityHandle.loadOrCreate`.
    public static let identityKeys = [
        "personalDeviceId",
        "personalKemPub", "personalKemPriv",
        "personalDsaPub", "personalDsaPriv",
    ]

    /// Longs are 8-byte big-endian (matches `RelayCursorStore` via `setLong`).
    public static func encodeLong(_ value: Int64) -> Data {
        var bigEndian = value.bigEndian
        return Data(bytes: &bigEndian, count: MemoryLayout<Int64>.size)
    }

    public static func decodeLong(_ data: Data) -> Int64? {
        guard data.count == MemoryLayout<Int64>.size else { return nil }
        return data.withUnsafeBytes { $0.load(as: Int64.self).bigEndian }
    }
}
