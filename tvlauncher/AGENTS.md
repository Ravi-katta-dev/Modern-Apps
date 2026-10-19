# AGENTS.md — tvlauncher/ (':tvlauncher')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `tvlauncher/` + allowed shared modules. Do not root-scan.

_Install: ./install tvlauncher (dev by default) for a USB-connected device. For a TV over
Wi-Fi: `sh tvlauncher/install-tv.sh <TV_IP>` builds, installs and offers to set it as the
default home; `sh tvlauncher/undo-tv.sh <TV_IP>` restores the stock home and uninstalls._
<!-- last-reviewed: 2026-10-19 -->
- Gradle: :tvlauncher / dir: tvlauncher/
- Package roots present: ui, data, domain, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: androidx.tv.material (via common-conventions-tv), datastore.preferences
- Metadata: metadata_data/tvlauncher.md (present)
- Rust: no / screenshotTest: no (TV fullscreen grid, Layoutlib cannot render it — cast:tv precedent)

## Overview
**Purpose:** Calm TV home-screen replacement in the tv-detox shape — app banner grid, HDMI inputs, clock header — rebuilt in Compose/Kotlin. No account, nothing uploaded; row order in DataStore.
**Key features:**
- 6-per-row 16:9 banner grid topped up from installed leanback apps, persisted order
- Bottom glass row: Play Store, HDMI inputs, Settings, Add
- D-pad focus scale + caption, long-OK edit menu (move/replace/remove/uninstall)
- Clock header with live network tile; HOME role so it can be the default launcher
**Architecture / data-flow:** `ui/` home/grid/bottom-row/edit-menu → `platform/TvHomeViewModel` (+ `TvUiContract`) → `data/TvPrefs` (DataStore); `domain/` is pure row-order math (move/replace/remove, 6-per-row) with no Android imports; `platform/TvInputs` owns HDMI via TvInputManager.
**Entry points + reading order:** `Route` → `MainActivity.kt` → `Navigation.kt` → `ui/TvHomeScreen.kt` → `platform/TvHomeViewModel.kt` → `domain/TvRowOrder.kt`.
**Gotchas:**
- New package, so the `:launcher` raw-animation exemption does NOT apply — focus motion goes through `library.ui` motion helpers
- TV banner art comes from `PackageManager.getActivityBanner()`, which has no repo precedent; loading is bounded-parallel with a non-reflowing placeholder
- Back on home is a no-op; HOME intent resets to the grid (no folder/page state to reset in v1)

## Key files
### Root — entry + navigation
- com/vayunmathur/tvlauncher/MainActivity.kt
- com/vayunmathur/tvlauncher/Navigation.kt
- com/vayunmathur/tvlauncher/Route.kt
### data/ — DataStore row order
- com/vayunmathur/tvlauncher/data/TvPrefs.kt
### domain/ — pure row math (no Android/Compose)
- com/vayunmathur/tvlauncher/domain/TvRowOrder.kt
### platform/ — ViewModel, apps, inputs, home role
- com/vayunmathur/tvlauncher/platform/TvHomeViewModel.kt
- com/vayunmathur/tvlauncher/platform/TvUiContract.kt
- com/vayunmathur/tvlauncher/platform/TvApps.kt
- com/vayunmathur/tvlauncher/platform/TvInputs.kt
### ui/ — clock header, grid, bottom row, edit menu
- com/vayunmathur/tvlauncher/ui/TvHomeScreen.kt
- com/vayunmathur/tvlauncher/ui/TvClockHeader.kt
- com/vayunmathur/tvlauncher/ui/TvAppGrid.kt
- com/vayunmathur/tvlauncher/ui/TvBottomRow.kt
- com/vayunmathur/tvlauncher/ui/TvEditMenu.kt

## Verify (this module only)
```
./gradlew :tvlauncher:compileDevKotlin
./gradlew :tvlauncher:lint
./gradlew :tvlauncher:checkMetadata
```
