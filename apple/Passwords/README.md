# EverySync Passwords — Apple shell (Phase 3a)

SwiftUI shell. **All merge / transport / crypto lives in the shared
XCFramework** (`everysync-core` KMP); this target renders UI, talks Keychain,
hosts the Credential Provider extension, and schedules background sync. Zero
sync logic in Swift.

## Architecture

```
PasswordsApp (SwiftUI) ──► EverysyncCore.xcframework ──► relay
        │                          │
        │ UI state only            │ merge / TOTP / PQC /
        │                          │ transport / cursors
        ▼                          ▼
Keychain (SecureStore)      App-Group envelope IPC
BGTaskScheduler periodic    (see ../IPC_CONTRACT.md)
ASCredentialProvider ext.
```

## KMP entry points this shell will call (nothing else)

| Kotlin FQN | Used for |
|---|---|
| `core.data.RemotePassword` | Envelope JSON on `passwords:<vaultId>` (see `recordChannel`) |
| `core.passwords.mergeCredentials` / `passkeysFor` | List + detail pairing. Explicit `PasskeyLink` wins both directions; auto-match needs domain AND username agreement on exactly one candidate; ties stay standalone. Deterministic order `(creationTime, syncId)`. Swift never re-implements this. |
| `core.passwords.Totp.generate(secret, epochSecond)` | 6-digit codes. Base32 RFC 4648 (padding stripped, unknown chars ignored), 30 s step, big-endian counter, dynamic truncation. HMAC-SHA1 actual is `HmacIos` (CryptoKit `HMAC<Insecure.SHA1>`). Swift only passes `Date().epochSeconds`. |
| `core.passwords.DomainMatch` / `PasskeyLink` | Autofill candidate matching + explicit link/detach. |
| `core.sync.adapters.PasswordKind` | `syncId=uid`, LWW on `updatedAt`, `contentHash = relayContentHash(canonical + "\0deleted=…")`, `contentKey` password `name␀username` / passkey `rpId␀credentialId`. Only explicit `deleted=true` envelopes are deletes (`isTombstoneEnvelope`). |
| `core.provider.SyncDriver` + `core.platform.RelayCursorStore` | Sync cycles; cursor keys `relay_cursor:<type>:<vault>:<channel>`. |
| `core.network.IosRelayTransport` | Ktor-Darwin + trust pinning (lands Phase 3; stub today). Envelopes: `Base64(AES_GCM)` per item; invites PQC-sealed to `inbox:<deviceId>`. |
| `core.network.RelayRoles` | Passwords vaults offer **EDITOR only — never VIEWER** (read-only passwords still disclose every secret). |
| `core.network.RelayLinks` | `everysync://join/<vault>?owner=…&type=…` + `https://ma.vayunmathur.com/es/share#id=…&owner=…&type=…`. |
| PQC cinterop (`IOS_PQC_NOTES.md`) | Exactly 10 fns: `pqc_kem_keygen/encaps/decaps`, `pqc_dsa_keygen/sign/verify`, `pqc_bundle_build/split`, `pqc_free/secure_zero` via `e2eePqc.def`. Never `identity_keygen`, `link_*`, `security_code`. |

## Delete semantics — HARD delete

Passwords hard-delete: rows vanish locally; only the app-side snapshot
remembers them. `PasswordKind.localDeletesAreAuthoritative == false`, so a
healthy-read absence IS a genuine local delete. `SyncSink.delete` removes the
row; merge-driven deletes always arrive as tombstone envelopes, never raw
deletes. See `../IPC_CONTRACT.md`.

## Credential Provider extension

`ASCredentialProviderViewController` subclass (see `PasswordsApp.swift`) links
the same XCFramework and calls `DomainMatch` — no Swift matching logic. It
reads via XPC from the containing app (App-Group container +
`NSFileCoordinator`; the extension never touches the relay directly).

## Keychain SecureStore

KMP `SecureStore` actual = Keychain `kSecClassGenericPassword`.
Keychain group shared with the Credential Provider extension; vault AES keys
never user-visible (share links carry ids only; keys move via PQC-sealed
inbox invites).

## BGTaskScheduler

`BGAppRefreshTaskRequest(identifier: "com.vayunmathur.everysync.passwords.refresh")`
periodic pull → hands records to `SyncDriver`; one-shot
`BGProcessingTaskRequest` after local edits. Scheduling only — the cycle runs
in the XCFramework.

## Info.plist / entitlements notes

- `NSFaceIDUsageDescription` — vault unlock (no password material leaves Keychain).
- `com.apple.security.application-groups`: `group.<team>.everysync.shared` (envelope IPC).
- `keychain-access-groups`: `$(AppIdentifierPrefix)com.vayunmathur.everysync.passwords(.autofill)`.
- `com.apple.developer.authentication-services.autofill-credential-provider` = YES (extension target).

## Swift must NOT

Re-implement merge pairing, TOTP framing/math, canonical JSON/hash, envelope
Base64/AES, role checks, or link parsing. If logic exists in Swift that also
exists in `everysync-core`, delete the Swift copy.

## Files (Phase 3)

| File | Role |
|---|---|
| `PasswordsApp.swift` | SwiftUI entry + vault list/detail contract; zero sync logic |
| `CredentialProvider.swift` | `ASCredentialProviderViewController`; reads over XPC, ranks via `DomainMatch` |
| `PasswordsXPCService.swift` | `com.vayunmathur.everysync.envelope-ipc` — exactly the 3 IPC ops |
| `Info.plist.reference` | Face ID string, BGTask id, `everysync://join` scheme |
| `Passwords.entitlements.reference` | App Group + shared Keychain group |
| `CredentialProvider.entitlements.reference` | Autofill entitlement (extension target only) |
| `../Shared/EnvelopeIPC.swift` | SQLite IPC store (shared with Notes/Hub) |
| `../Shared/BlobStore.swift` | Blob sidecar (shared) |
| `../Shared/KeychainSecureStore.swift` | Keychain layout contract (Kotlin implements) |
| `../Shared/EverysyncCoreCrypto.swift` | CryptoKit AES-GCM C bridge (fills the Rust AES gap) |
| `../Shared/BackgroundSync.swift` | BGTask registration helpers |
