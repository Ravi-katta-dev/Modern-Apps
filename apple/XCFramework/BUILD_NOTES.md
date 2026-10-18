# XCFramework build — `EverysyncCore.xcframework` (Phase 3, Mac only)

Builds the shared KMP core (`library/everysync-core`) as an XCFramework with
the `iosArm64 + iosSimulatorArm64` slices, linked against the Rust `e2ee_pqc`
static lib for the 10 PQC cinterop functions. Swift never calls Rust directly
— only via this XCFramework.

> Requires macOS + Xcode. This directory holds the script + interface docs;
> nothing here runs on the Windows box. Verified by inspection only.

## Prerequisites (Mac)

- Xcode 15+ with iOS 17 SDK (`xcode-select -p` points at it).
- Rust targets: `rustup target add aarch64-apple-ios aarch64-apple-ios-sim`
  (simulator slice is ARM64-only — no Intel slice; Rosetta fallback is out
  of scope for Phase 3).
- `cbindgen` (`cargo install cbindgen`) — generates `e2ee_pqc.h` from
  `library/e2ee-p2p/src/main/rust/src/c_bridge.rs`.
- KMP iOS plugin version == Kotlin version (see
  `gradle/libs.versions.toml`); `iosArm64 + iosSimulatorArm64` targets in
  `library/everysync-core/build.gradle.kts`.

## Steps

1. Build the Rust static lib per slice (from repo root):
   ```sh
   cargo build --target aarch64-apple-ios --release \
     --manifest-path library/e2ee-p2p/src/main/rust/Cargo.toml
   cargo build --target aarch64-apple-ios-sim --release \
     --manifest-path library/e2ee-p2p/src/main/rust/Cargo.toml
   ```
   NOTE: `IOS_PQC_NOTES.md` says `x86_64-apple-ios` for the simulator slice;
   that target is Intel-only and wrong for Apple-Silicon simulators. This doc
   uses `aarch64-apple-ios-sim` — the drift is recorded in
   `../IPC_CONTRACT.md` appendix review notes (contract semantics unaffected;
   only the build target triple changed).
2. Generate the C header (consumed by `e2eePqc.def`):
   ```sh
   cbindgen --crate e2ee-p2p --output e2ee_pqc.h \
     --config library/e2ee-p2p/src/main/rust/cbindgen.toml
   ```
   (If no `cbindgen.toml` exists, create one pinning `language = "C"`,
   `usize_is_size_t = true`, and the `pqc_` export prefix. The reference
   header `e2ee_pqc.reference.h` in this directory shows the expected 10-fn
   surface.)
3. Place `e2ee_pqc.h` in `src/nativeInterop/cinterop/e2eePqc/` next to
   `e2eePqc.def` (see `IOS_PQC_NOTES.md` for the canonical `.def` text —
   copied verbatim into `e2eePqc.def` here).
4. Build the XCFramework:
   ```sh
   ./gradlew :library:everysync-core:assembleEverysyncCoreXCFramework
   ```
   (Task name must match the KMP `XCFramework` block the Mac adds to the
   module build file in Phase 3 — confirm on the Mac.)
   Equivalent manual slice assembly (if the Gradle task is unavailable):
   ```sh
   ./gradlew :library:everysync-core:compileKotlinIosArm64 \
             :library:everysync-core:compileKotlinIosSimulatorArm64
   xcodebuild -create-xcframework \
     -library <iosArm64 .a> -headers <generated headers> \
     -library <simArm64 .a> -headers <generated headers> \
     -output EverysyncCore.xcframework
   ```
   Or run `./build_xcframework.sh` in this directory (wraps steps 1–4).
5. Link the XCFramework into the three app targets + the Credential Provider
   extension + the Share extension (all five link the SAME XCFramework —
   merge/match/TOTP stay single-sourced).
6. Re-run relay conformance from the iOS simulator against Android + desktop
   (see `../E2E_CHECKLIST.md`): register/getkey/append/pull, channel-name
   assertions, `B64(AES_GCM)` record shape, double-wrapped
   roster/epoch/transfer envelopes, PQC-sealed invite to `inbox:<device>`,
   `everysync://join` parse, plus the three invariants (null-on-failed-pull
   abort, tombstone-only delete, keys-never-visible).

## Memory discipline (matches JNA)

Every `*mut u8 + len` out-param buffer from the 10 PQC fns is `Box::into_raw`
— the Kotlin cinterop actual copies to a `ByteArray`, then calls `pqc_free`;
priv/SS bytes are zeroed via `pqc_secure_zero`. The two CryptoKit bridge fns
(`everysync_aes_gcm_encrypt/decrypt` in `../Shared/EverysyncCoreCrypto.swift`)
follow the same discipline via `everysync_crypto_free`.

## What Swift must NOT do

Re-implement merge pairing, TOTP framing/math, canonical JSON/hash, envelope
Base64/AES, role checks, or link parsing. If logic exists in Swift that also
exists in `everysync-core`, delete the Swift copy.
