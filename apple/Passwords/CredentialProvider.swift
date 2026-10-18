// EverySync Passwords — Credential Provider extension.
//
// `ASCredentialProviderViewController` subclass. Reads credentials over XPC
// from the containing app (App-Group container + `NSFileCoordinator` — see
// `PasswordsXPCService.swift`); candidate ranking via XCFramework
// `DomainMatch` — NO Swift matching logic. The extension never touches the
// relay, never merges, never hashes, never encrypts.
//
// Flow:
// 1. `prepareCredentialList(for:)` — asks the host XPC service for the
//    vault's password/passkey envelopes, projects display rows
//    (service name + username only), and returns matching
//    `ASPasswordCredentialIdentity` items. Matching =
//    `DomainMatch.normalizeSite` + `isSameSiteOrSubdomain` in the XCFramework.
// 2. `provideCredentialWithoutUserInteraction(for:)` — returns the stored
//    `ASPasswordCredential` for a previously-selected identity.
// 3. `prepareInterfaceToProvideCredential(for:)` — presents confirmation UI,
//    then provides the credential.
//
// MAC-CHECK (needs xcodebuild on a Mac):
// - Extension target links the same `EverysyncCore.xcframework`.
// - `com.apple.developer.authentication-services.autofill-credential-provider`
//   entitlement = YES on the extension target only.
// - Shared Keychain group with the containing app (see
//   `Passwords.entitlements.reference`).

import AuthenticationServices
import EverysyncCore

/// Autofill extension. All candidates come from the host app over XPC;
///
/// `DomainMatch` ranking stays in the XCFramework.
final class EverySyncCredentialProvider: ASCredentialProviderViewController {
    /// XPC connection to the containing app's envelope service. The extension
    /// process cannot assume direct store access, so EVERY read goes through
    /// `com.vayunmathur.everysync.envelope-ipc` (see `PasswordsXPCService.swift`).
    private var xpc: NSXPCConnection?

    override func prepareCredentialList(for serviceIdentifiers: [ASCredentialServiceIdentifier]) {
        // TODO(Mac): open the XPC connection; fetch envelope rows for the
        // active vault; rank via XCFramework `DomainMatch` over each stored
        // `websites[]` / `rpId`; reply with matching identities.
        // No Swift matching logic — call the XCFramework.
    }

    override func provideCredentialWithoutUserInteraction(for credentialIdentity: ASPasswordCredentialIdentity) {
        // TODO(Mac): fetch the single envelope by syncId over XPC; reply with
        // `ASPasswordCredential(user:username, password:password)`.
        // Never log or cache the secret outside this call.
    }

    override func prepareInterfaceToProvideCredential(for credentialIdentity: ASPasswordCredentialIdentity) {
        // TODO(Mac): present confirmation UI (Face ID gate via
        // `NSFaceIDUsageDescription`), then provide the credential as above.
    }
}
