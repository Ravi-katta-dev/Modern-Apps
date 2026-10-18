# iOS platform actuals — implementation notes for the Mac (Kotlin side)

The live actuals land in `library/everysync-core/src/iosMain/` on the Mac
(needs the Apple SDK — cannot compile on this Windows box). The current
`iosMain` stubs (`CryptoIos.kt`, `IosRelayTransport.kt`, `HmacIos.kt`) throw
`UnsupportedOperationException` until Phase 3; this doc specifies exactly what
replaces each throw, so the Mac work is mechanical. **Do NOT modify the core:**
`commonMain` contracts are frozen — only `iosMain` actuals change.

## `expect` -> `actual` map

| `commonMain` expect / interface | `iosMain` actual | Backing API |
|---|---|---|
| `crypto.CryptoKt.AesGcm` | `CryptoIos.kt` (`actual object AesGcm`) | CryptoKit `AES.GCM` via the `everysync_aes_gcm_encrypt/decrypt` C bridge in `../Shared/EverysyncCoreCrypto.swift` (bound through the SAME cinterop module as the 10 PQC fns — see `e2eePqc.def` + `e2ee_pqc.reference.h`). Layout `[12B nonce][ct][16B tag]` == JCE layout. `newContentKey()` = 32B from `PlatformRandom`. Fills the Rust AES gap — no AES added to Rust. |
| `crypto.CryptoKt.PlatformRandom` | `CryptoIos.kt` (`actual object PlatformRandom`) | `SecRandomCopyBytes(kSecRandomDefault, …)` via cinterop (platform Security framework). |
| `passwords.TotpKt.HmacSha1` | `passwords/HmacIos.kt` (`actual object HmacSha1`) | CryptoKit `HMAC<Insecure.SHA1>` (pure framing stays in `Totp`: base32 RFC 4648, 30s step, big-endian counter, dynamic truncation). |
| `network.PqcCrypto` (interface, not expect) | New `iosMain/.../network/PqcIos.kt` | The 10 bound cinterop fns (`e2ee.pqc.native.*`): `generateKem` = `pqc_kem_keygen`; `generateDsa` = `pqc_dsa_keygen`; `bundle` = `pqc_bundle_build`; `splitBundle` = `pqc_bundle_split` (null on parse failure); `seal` = encaps + `BundleFraming.prefix(encap, aes)` where `aes` = CryptoKit AES-GCM under `SHA256(BE32(1)‖rawSS)` (identical KDF to Kotlin `concat_kdf` — see `c_bridge.rs` comment); `decrypt` = split + decaps + AES open; `sign` = `pqc_dsa_sign`; `verify` = `pqc_dsa_verify` (false on 0 AND -1). Copy-then-`pqc_free`; zero priv/SS via `pqc_secure_zero`. |
| `network.RelayTransport` (interface) | `network/IosRelayTransport.kt` (exists as stub) | Ktor-Darwin engine + sec-trust pinning from the common-resource DERs (`src/commonMain/resources/ca/` via `syncCaResources`). Envelope codec (`appendRecords`/`pullRecords`), channel names, and null-on-failure live in `SharedRelayTransport` — the actual only supplies `RelayHttpClient.post/subscribe`. |
| `platform.PlatformKt.SecureStore` (interface) | New `iosMain/.../platform/SecureStoreIos.kt` | Keychain `kSecClassGenericPassword` per `../Shared/KeychainSecureStore.swift` (service `com.vayunmathur.everysync.securestore`, shared access group, `AfterFirstUnlockThisDeviceOnly`; longs big-endian Int64; `onlyIfAbsent` under a mutex). |
| `platform.PlatformKt.SyncScheduler` (interface) | New `iosMain/.../platform/SyncSchedulerIos.kt` | `BGTaskScheduler.submit/cancel` for the five identifiers in `../Shared/BackgroundSync.swift` (refresh intervals from `schedulePeriodic`; `syncNow` = `scheduleProcessing`). Scheduling only — cycles run in `SyncDriver`. |
| `platform.PlatformKt.SyncSink` (interface) | Kotlin shims per type (new `iosMain/.../sinks/`) | `RelayStore` shims over the envelope IPC SQLite files (SAME schema as `../Shared/EnvelopeIPC.swift` — SQLDelight or direct sqlite via cinterop; WAL + `busy_timeout=5000`). Table map + baseline sidecar: see `../Hub/IPCRelayStore.swift`. |

## Slices to run on the Mac (scoped, never whole-repo)

```sh
./gradlew :library:everysync-core:compileKotlinIosArm64 \
          :library:everysync-core:compileKotlinIosSimulatorArm64
./gradlew :library:everysync-core:lint
./gradlew :library:everysync-core:checkMetadata
xcodebuild -project apple/EverySync.xcodeproj -scheme Passwords \
  -destination 'platform=iOS Simulator,name=iPhone 16' build
xcodebuild -project apple/EverySync.xcodeproj -scheme Notes \
  -destination 'platform=iOS Simulator,name=iPhone 16' build
xcodebuild -project apple/EverySync.xcodeproj -scheme Hub \
  -destination 'platform=iOS Simulator,name=iPhone 16' build
```

(The Xcode project itself is created on the Mac — no `.xcodeproj` is
checked in from here. Reference plists/entitlements live next to each
target: `Passwords/`, `Notes/`, `Hub/`.)
