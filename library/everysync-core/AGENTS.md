# AGENTS.md — library/everysync-core/ (':library:everysync-core')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/everysync-core/` + allowed shared modules. Do not root-scan.

- Gradle: :library:everysync-core / dir: library/everysync-core/
- Targets: android + jvm (desktop) + iosArm64 + iosSimulatorArm64 (KMP plugin version == Kotlin version)
- Package: `com.vayunmathur.everysync.core` (commonMain) + platform actuals
- Entry files: `commonMain/sync/RelayMerge.kt`, `commonMain/data/RemoteModels.kt` (Phase 1A moves)
- Deps: kotlinx-coroutines-core, kotlinx-serialization-json, kotlinx-datetime (common only)
- Metadata: none (libraries have none by design)
- Rust: no (binds the `:library:e2ee-p2p` crate via JNI/JNA/cinterop — no new Rust)

## Overview
**Purpose:** Shared KMP sync brain for the EverySync E2EE relay protocol — merge, vault policy, DTOs, formats. No Android API in commonMain; platform coupling behind expect/actual (`RelayTransport`, `SecureStore`, `SyncScheduler`, `SyncSink`, `AesGcm`, `SyncDigest`).
**Invariants (verbatim from the Android impl):** a failed pull returns null = abort, never empty; only explicit tombstones delete; vault keys never user-visible. Relay contract: `https://findfamily.cc/personal` + `wss://…/ws`, `POST /register|/getkey|/append|/pull`.
**Gotchas:** never depend on `:library:log` / `:library:network` / `:library:room` from commonMain (Android-only, no common variant); FIRST_PARTY pinning DERs are copied by `syncCaResources` into `src/commonMain/resources/ca/` (gitignored build output) and loaded at runtime by the OkHttp actuals.

## Verify (this module only)
```
./gradlew :library:everysync-core:compileKotlinJvm
./gradlew :library:everysync-core:lint
```
