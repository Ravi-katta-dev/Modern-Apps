// EverySync Passwords — Phase 3a SwiftUI shell.
//
// Zero sync logic in Swift. All merge / transport / crypto lives in the
// shared XCFramework (everysync-core KMP). This file only declares the UI
// entry point, the Credential Provider host, and — in comments — the exact
// KMP entry points Phase 3 will call. Nothing here computes, merges,
// hashes, or encrypts.

import SwiftUI
import AuthenticationServices
import EverysyncCore

// MARK: - KMP entry points (Phase 3 wiring, for reference only)
//
// data:        com.vayunmathur.everysync.core.data.RemotePassword
// merge:       com.vayunmathur.everysync.core.passwords.mergeCredentials
//              com.vayunmathur.everysync.core.passwords.passkeysFor
//              com.vayunmathur.everysync.core.passwords.PasskeyLink
//              com.vayunmathur.everysync.core.passwords.DomainMatch
// totp:        com.vayunmathur.everysync.core.passwords.Totp.generate
// kind:        com.vayunmathur.everysync.core.sync.adapters.PasswordKind
// driver:      com.vayunmathur.everysync.core.provider.SyncDriver
// cursors:     com.vayunmathur.everysync.core.platform.RelayCursorStore
// securestore: com.vayunmathur.everysync.core.platform.SecureStore (Keychain actual)
// transport:   com.vayunmathur.everysync.core.network.IosRelayTransport
// channels:    recordChannel("passwords", vaultId) -> "passwords:<vaultId>"
// roles:       RelayRoles — passwords offer EDITOR only, never VIEWER
// links:       RelayLinks.parseJoinLink / .shareLinkFor
// pqc:         10 fns per IOS_PQC_NOTES.md (e2eePqc cinterop)

@main
struct PasswordsApp: App {
    // TODO(Phase 3): create SyncDriver + IosRelayTransport from the
    //  XCFramework; register BGTaskScheduler handlers (see README.md).
    var body: some Scene {
        WindowGroup {
            PasswordsRootView()
        }
    }
}

struct PasswordsRootView: View {
    // TODO(Phase 3): vault list driven by SyncDriver snapshots via
    //  RelayCursorStore; rows rendered from RemotePassword envelopes.
    var body: some View {
        Text("EverySync Passwords")
    }
}

// MARK: - Credential Provider extension host

/// Autofill extension. Reads credentials over XPC from the containing app
/// (App-Group container + NSFileCoordinator); matching via XCFramework
/// `DomainMatch`. No Swift matching logic.
final class EverySyncCredentialProvider: ASCredentialProviderViewController {
    override func prepareCredentialList(for serviceIdentifiers: [ASCredentialServiceIdentifier]) {
        // TODO(Phase 3): query containing app over XPC, rank via DomainMatch.
    }

    override func provideCredentialWithoutUserInteraction(for credentialIdentity: ASPasswordCredentialIdentity) {
        // TODO(Phase 3): return the stored credential for this identity.
    }

    override func prepareInterfaceToProvideCredential(for credentialIdentity: ASPasswordCredentialIdentity) {
        // TODO(Phase 3): present confirmation UI, then provide the credential.
    }
}
