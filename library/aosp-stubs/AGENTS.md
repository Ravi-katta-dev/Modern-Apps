# AGENTS.md — library/aosp-stubs/ (':library:aosp-stubs')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/aosp-stubs/` + allowed shared modules. Do not root-scan.

<!-- last-reviewed: 2026-10-16 -->
- Gradle: :library:aosp-stubs / dir: library/aosp-stubs/
- Package roots present: (compile-only hidden-API stubs — merged from seven former `*-stubs` modules)
- Entry files: (none — stubs only, no runtime code)
- Deps: (none)
- Metadata: metadata_data/library-aosp-stubs.md (absent — libraries have none by design)
- Rust: no / screenshotTest: no

## Overview
**Purpose:** Compile-only stubs for Android hidden APIs — merged from seven former `*-stubs` modules into one. Lets privileged modules (appstore, cast, updater, parentalcontrols, euicc) reference system APIs without the SDK exposing them.
**How to use:** Depend on it, call the hidden API, guard with runtime availability checks — stubs satisfy the compiler, the OS provides the implementation.
**Entry points:** The stub matching your hidden API (verify via scoped `ls` — card had no Key files at last review).
**Gotchas:** Stubs are signatures only — calling one on an OS build that lacks the API crashes at runtime. This card was a near-empty stub; verify against `ls` before trusting it.

## Key files
- (Verify via scoped `ls` — card had no Key files at last review; add them when touching this module.)

## Verify (this module only)
```
./gradlew :library:aosp-stubs:compileDevKotlin
./gradlew :library:aosp-stubs:lint
```
