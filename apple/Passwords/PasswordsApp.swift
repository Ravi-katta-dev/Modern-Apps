// EverySync Passwords — Phase 3 SwiftUI shell.
//
// ZERO sync logic in Swift. All merge (`mergeCredentials` / `passkeysFor` /
// `PasskeyLink`), TOTP (`Totp.generate`), matching (`DomainMatch`), envelope
// codec (`PasswordKind.toJson/fromJson`), roles (`RelayRoles`), links
// (`RelayLinks`), transport (`IosRelayTransport`), and cursors
// (`RelayCursorStore`) live in the shared XCFramework. This target:
// - renders vault list / detail / TOTP codes from XCFramework snapshots,
// - hosts the Credential Provider extension (see `CredentialProvider.swift`),
// - exposes the XPC envelope service (see `PasswordsXPCService.swift`),
// - schedules background sync (see `../Shared/BackgroundSync.swift`).
//
// Delete semantics — HARD delete (verbatim): passwords rows vanish locally;
// only the app-side snapshot remembers them.
// `PasswordKind.localDeletesAreAuthoritative == false`, so a healthy-read
// absence IS a genuine local delete. `SyncSink.delete` removes the row;
// merge-driven deletes always arrive as tombstone envelopes, never raw.
// See `../IPC_CONTRACT.md`.

import SwiftUI
import BackgroundTasks
import EverysyncCore

// MARK: - KMP entry points (Phase 3 wiring — call, never re-implement)
//
// data:        core.data.RemotePassword (uid / isPasskey / name / username /
//              email / password / note / totpSecret / websites[] / rp* half /
//              creationTime / lastUsedTime / signCount / linkedPasswordSyncId /
//              deleted / updatedAt)
// merge:       core.passwords.mergeCredentials([CredentialDto], [PasskeyDto])
//                -> MergeResult(passkeysByPasswordSyncId, standalone)
//              core.passwords.passkeysFor(password, passwords, passkeys)
//              core.passwords.PasskeyLink (Auto / Detached / To(syncId))
// totp:        core.passwords.Totp.generate(secret, epochSecond) — Swift only
//              passes `Int64(Date().timeIntervalSince1970)`; CryptoKit HMAC is
//              behind the XCFramework's `HmacIos` actual.
// match:       core.passwords.DomainMatch.normalizeSite / isSameSiteOrSubdomain
// kind:        core.sync.adapters.PasswordKind (syncId=uid, LWW updatedAt,
//              contentKey password `name␀username` / passkey `rpId␀credentialId`,
//              `isTombstoneEnvelope` = `deleted`)
// driver:      core.provider.SyncDriver.drive(kind, store, remote, append, now)
// cursors:     core.platform.RelayCursorStore (`relay_cursor:<type>:<vault>:<channel>`)
// securestore: core.platform.SecureStore (Keychain actual — see
//              ../Shared/KeychainSecureStore.swift; vault AES keys NEVER
//              surface in Swift)
// transport:   core.network.IosRelayTransport (`recordChannel("passwords",
//              vaultId)` -> `passwords:<vaultId>`; envelopes
//              `Base64(AES_GCM)` per item; invites PQC-sealed to `inbox:<device>`)
// roles:       RelayRoles — passwords offer EDITOR only, NEVER VIEWER
//              (read-only passwords still disclose every secret)
// links:       RelayLinks.parseJoinLink / .shareLinkFor
//              (`everysync://join/<vault>?owner=…&type=…`)
// pqc:         exactly 10 fns per IOS_PQC_NOTES.md via `e2eePqc` cinterop.
//              Swift never calls Rust directly — only via the XCFramework.

/// Vault list entry, projected from `RemotePassword` envelopes by the
/// XCFramework. Swift holds display strings only — no secrets leave the
/// envelope except into the visible detail view / autofill response.
struct PasswordRow: Identifiable {
    /// Relay sync identity (`RemotePassword.uid`).
    let id: String
    let name: String
    let username: String
    /// True for the passkey half (`RemotePassword.isPasskey`).
    let isPasskey: Bool
    let rpId: String
    /// Attached passkeys, from `passkeysFor` in the XCFramework.
    let passkeyCount: Int
}

@main
struct PasswordsApp: App {
    init() {
        // Register the periodic pull; the cycle itself runs in the XCFramework.
        // MAC-CHECK: identifier must match Info.plist
        // `BGTaskSchedulerPermittedIdentifiers`.
        BackgroundSync.register(handlers: [
            "com.vayunmathur.everysync.passwords.refresh": { task in
                // TODO(Mac): call XCFramework SyncDriver cycle for each vault,
                // then re-schedule + `task.setTaskCompleted(success:)`.
                // (Kotlin `SyncScheduler` actual owns the schedule; this
                // handler is the wakeup entry.)
                task.setTaskCompleted(success: true)
            },
        ])
    }

    var body: some Scene {
        WindowGroup {
            PasswordsRootView()
        }
    }
}

struct PasswordsRootView: View {
    // TODO(Mac): vault list driven by SyncDriver snapshots via
    // RelayCursorStore; rows projected from RemotePassword envelopes by
    // `mergeCredentials` in the XCFramework. TOTP via `Totp.generate`.
    // Search filters on name/username only (never on secret material).
    @State private var query = ""

    var body: some View {
        NavigationStack {
            VStack {
                // TODO(Mac): replace with the XCFramework-driven list.
                // Placeholder keeps the target compiling on Windows/Linux CI
                // where EverysyncCore / SwiftUI previews are unavailable.
                Text("EverySync Passwords")
                    .navigationTitle("Passwords")
            }
            .searchable(text: $query)
        }
    }
}

// MARK: - Detail view contract (Mac implements against XCFramework types)
//
// - Detail shows password fields + `Totp.generate(secret, now)` code.
// - Passkey rows show `rpId / credentialId / userName` + link state
//   (`PasskeyLink.fromColumn(linkedPasswordSyncId)`); link/detach writes go
//   through the envelope IPC as `linkedPasswordSyncId` field updates — Swift
//   never computes merge pairing.
// - Delete = HARD delete via `deleteByVault` (row vanishes); the app-side
//   snapshot (XCFramework) remembers it for propagation.
// - Share sheet offers EDITOR only (`RelayRoles.offerable("passwords")`).
//   Vault keys never user-visible: links carry ids only.
