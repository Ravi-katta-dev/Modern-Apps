# AGENTS.md — launcher/ (':launcher')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `launcher/` + allowed shared modules. Do not root-scan.

_Install: ./install launcher (dev by default)._
<!-- last-reviewed: 2026-10-16 -->
- Gradle: :launcher / dir: launcher/
- Package roots present: ui, data, domain, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :library:room
- Metadata: metadata_data/launcher.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** Offline home-screen replacement — paged grid, hotseat, folders, searchable drawer, real widget hosting. Layout in encrypted Room; no account, nothing uploaded.
**Key features:**
- Workspace grid with drag/drop/reorder/folder-merge, drop-bar targets, page-edge dwell, widget resize frames (`ui/components/` drag system + `domain/` placement math)
- App drawer with fast scroll + predictions; widget host incl. configurable providers (`platform/LauncherWidgetHost`)
- Icon cache, app-change monitor, privilege-gated surfaces; settings pages
**Architecture / data-flow:** `ui/` home/drawer/folder/settings → `platform/LauncherViewModel` (+ `LauncherUiContract`) → `data/LauncherRepository` over Room; `domain/` is pure grid math (occupancy, placement, reorder, folders, hotseat, pagination) — the single biggest pure-logic area in any app, testable without Compose.
**Entry points + reading order:** `Route` → `MainActivity.kt` → `Navigation.kt` → `ui/HomeWorkspace.kt` → `platform/LauncherViewModel.kt` → `domain/GridPlacer.kt` + `domain/ReconcileUseCase.kt`.
**Gotchas:**
- Exempt from the raw-animation rule: built on `animateBounds`/`Animatable` — do NOT "migrate" its motion to library helpers
- Reconcile (installed-apps vs stored layout) runs on package changes — launcher bugs after installs live in `ReconcileUseCase` + `LauncherAppsMonitor`
- Drag input math is in `domain/`, gesture plumbing in `ui/components/` — keep them separated

## Key files
### Root — entry + navigation
- com/vayunmathur/launcher/MainActivity.kt
- com/vayunmathur/launcher/Navigation.kt
- com/vayunmathur/launcher/Route.kt
### data/ — encrypted layout store
- com/vayunmathur/launcher/data/LauncherDatabase.kt
- com/vayunmathur/launcher/data/LauncherRepository.kt
- com/vayunmathur/launcher/data/LauncherItemEntity.kt
### domain/ — pure grid math (no Android/Compose)
- com/vayunmathur/launcher/domain/ReconcileUseCase.kt
- com/vayunmathur/launcher/domain/GridPlacer.kt
- com/vayunmathur/launcher/domain/GridReorder.kt
- com/vayunmathur/launcher/domain/CellOccupancy.kt
- com/vayunmathur/launcher/domain/CellRect.kt
- com/vayunmathur/launcher/domain/ContainerRef.kt
- com/vayunmathur/launcher/domain/AutoPlacer.kt
- com/vayunmathur/launcher/domain/DropBarTargets.kt
- com/vayunmathur/launcher/domain/FolderMerge.kt
- com/vayunmathur/launcher/domain/FolderRules.kt
- com/vayunmathur/launcher/domain/GridPreview.kt
- com/vayunmathur/launcher/domain/HotseatArrange.kt
- com/vayunmathur/launcher/domain/LauncherItemType.kt
- com/vayunmathur/launcher/domain/LauncherTuning.kt
- com/vayunmathur/launcher/domain/PageCount.kt
- com/vayunmathur/launcher/domain/ReorderDwell.kt
- com/vayunmathur/launcher/domain/FastScroll.kt
- com/vayunmathur/launcher/domain/WidgetResize.kt
### platform/ — ViewModel, monitor, widgets, bridge
- com/vayunmathur/launcher/platform/LauncherViewModel.kt
- com/vayunmathur/launcher/platform/LauncherUiContract.kt
- com/vayunmathur/launcher/platform/LauncherAppsMonitor.kt
- com/vayunmathur/launcher/platform/LauncherPredictions.kt
- com/vayunmathur/launcher/platform/LauncherWidgetHost.kt
- com/vayunmathur/launcher/platform/LauncherWidgets.kt
- com/vayunmathur/launcher/platform/IconCache.kt
- com/vayunmathur/launcher/platform/ActivityBridge.kt
- com/vayunmathur/launcher/platform/LauncherPrivilege.kt
### ui/ + ui/components/ — workspace, drawer, drag system
- com/vayunmathur/launcher/ui/HomeWorkspace.kt
- com/vayunmathur/launcher/ui/HomePage.kt
- com/vayunmathur/launcher/ui/HomeContent.kt
- com/vayunmathur/launcher/ui/WorkspacePage.kt
- com/vayunmathur/launcher/ui/WorkspaceGrid.kt
- com/vayunmathur/launcher/ui/HomeGestureRoot.kt
- com/vayunmathur/launcher/ui/HomeDropLogic.kt
- com/vayunmathur/launcher/ui/HomeLaunch.kt
- com/vayunmathur/launcher/ui/HomeEffects.kt
- com/vayunmathur/launcher/ui/HomeOverlays.kt
- com/vayunmathur/launcher/ui/HomePopups.kt
- com/vayunmathur/launcher/ui/HomeConstants.kt
- com/vayunmathur/launcher/ui/HomeDrawerSwipe.kt
- com/vayunmathur/launcher/ui/WorkspaceOptionsContent.kt
- com/vayunmathur/launcher/ui/HotseatBar.kt
- com/vayunmathur/launcher/ui/FolderContent.kt
- com/vayunmathur/launcher/ui/DrawerContent.kt
- com/vayunmathur/launcher/ui/DrawerCells.kt
- com/vayunmathur/launcher/ui/DrawerFastScroll.kt
- com/vayunmathur/launcher/ui/ItemMenuContent.kt
- com/vayunmathur/launcher/ui/SettingsPage.kt
- com/vayunmathur/launcher/ui/SettingsContent.kt
- com/vayunmathur/launcher/ui/WidgetPickerContent.kt
- com/vayunmathur/launcher/ui/components/LauncherDragController.kt
- com/vayunmathur/launcher/ui/components/DragLayer.kt
- com/vayunmathur/launcher/ui/components/DragFeedback.kt
- com/vayunmathur/launcher/ui/components/DragSourceModifier.kt
- com/vayunmathur/launcher/ui/components/DropTargetModifier.kt
- com/vayunmathur/launcher/ui/components/DropBar.kt
- com/vayunmathur/launcher/ui/components/CellLayout.kt
- com/vayunmathur/launcher/ui/components/CellPlacement.kt
- com/vayunmathur/launcher/ui/components/LauncherDragInput.kt
- com/vayunmathur/launcher/ui/components/LauncherDragInputSection.kt
- com/vayunmathur/launcher/ui/components/LauncherAppIcon.kt
- com/vayunmathur/launcher/ui/components/LauncherItemIcon.kt
- com/vayunmathur/launcher/ui/components/LauncherPopup.kt
- com/vayunmathur/launcher/ui/components/LauncherPopupSurface.kt
- com/vayunmathur/launcher/ui/components/MergeRing.kt
- com/vayunmathur/launcher/ui/components/ReorderPreview.kt
- com/vayunmathur/launcher/ui/components/PageIndicator.kt
- com/vayunmathur/launcher/ui/components/PageEdgeDwell.kt
- com/vayunmathur/launcher/ui/components/FastScrollStrip.kt
- com/vayunmathur/launcher/ui/components/HostedWidget.kt
- com/vayunmathur/launcher/ui/components/MissingWidget.kt
- com/vayunmathur/launcher/ui/components/WidgetResizeFrame.kt
- com/vayunmathur/launcher/ui/components/AppWindowBounds.kt

## Verify (this module only)
```
./gradlew :launcher:compileDevKotlin
./gradlew :launcher:lint
./gradlew :launcher:checkMetadata
```
