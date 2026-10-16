# AGENTS.md — library/locationprovider/ (':library:locationprovider')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/locationprovider/` + allowed shared modules. Do not root-scan.

<!-- last-reviewed: 2026-10-16 -->
- Gradle: :library:locationprovider / dir: library/locationprovider/
- Package roots present: provider
- Entry files: (see sources — location abstraction consumed by networklocation + apps)
- Deps: (verify in build.gradle.kts before adding)
- Metadata: metadata_data/library-locationprovider.md (absent — libraries have none by design)
- Rust: no / screenshotTest: no

## Overview
**Purpose:** Location abstraction over GPS/network providers — the interface `networklocation/` implements and apps consume, so location callers never touch providers directly.
**How to use:** Depend on the abstraction, not the implementation; `networklocation/` is the system-backed provider.
**Entry points:** Read the `provider/` sources first (card Key files were empty — fill in from `ls` when touching this module).
**Gotchas:** This card was a near-empty stub (no Key files) — verify against `ls` before trusting it.

## Key files
- (Verify via scoped `ls` — card had no Key files at last review; add them when touching this module.)

## Verify (this module only)
```
./gradlew :library:locationprovider:compileDevKotlin
./gradlew :library:locationprovider:lint
```
