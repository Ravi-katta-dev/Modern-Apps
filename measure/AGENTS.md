# AGENTS.md — measure/ (':measure')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `measure/` + allowed shared modules. Do not root-scan.

_Install: ./install measure (dev by default)._
<!-- last-reviewed: 2026-10-16 -->
- Gradle: :measure / dir: measure/
- Package roots present: ui, data, domain, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: (none declared — camera + IMU + Rust VIO, no Play Services)
- Metadata: metadata_data/measure.md (present)
- Rust: yes / screenshotTest: yes

## Overview
**Purpose:** Offline measuring toolkit — compass, level, ruler, protractor + AR distance/area over a from-scratch visual-inertial odometry engine. Real metres, no Play Services.
**Key features:** Per-tool pages (AR/compass/level/ruler/saved/settings) + diagnostics; `domain/` pure geometry (level math, tilt, polygons, units); `platform/sensor/` IMU recording; camera intrinsics; Rust `measure_vio` behind `domain/MeasureNative`.
**Entry points:** `Route` → `MainActivity.kt` → `Navigation.kt` → `ui/ArMeasurePage.kt` → `platform/MeasureViewModel.kt` → `domain/MeasureNative.kt`.
**Gotchas:** VIO needs device calibration + motion — test measuring by moving, not screenshots; intrinsics vary per device.

## Key files
### Root — entry + navigation
- com/vayunmathur/measure/MainActivity.kt
- com/vayunmathur/measure/Navigation.kt
- com/vayunmathur/measure/Route.kt
### data/ — saved measurements
- com/vayunmathur/measure/data/model/Models.kt
### domain/ — pure geometry + Rust VIO bridge
- com/vayunmathur/measure/domain/MeasureNative.kt — `measure_vio` JNI bridge
- com/vayunmathur/measure/domain/LevelMath.kt
- com/vayunmathur/measure/domain/Tilt.kt
- com/vayunmathur/measure/domain/HeldOrientation.kt
- com/vayunmathur/measure/domain/Polygon.kt
- com/vayunmathur/measure/domain/Units.kt
### platform/ — ViewModel, intrinsics, IMU
- com/vayunmathur/measure/platform/MeasureViewModel.kt
- com/vayunmathur/measure/platform/MeasureUiContract.kt
- com/vayunmathur/measure/platform/CameraIntrinsics.kt
- com/vayunmathur/measure/platform/sensor/ImuRecorder.kt
- com/vayunmathur/measure/platform/sensor/TiltSensor.kt
### ui/ + ui/components/ — tool pages
- com/vayunmathur/measure/ui/ArMeasurePage.kt
- com/vayunmathur/measure/ui/ArMeasureContent.kt
- com/vayunmathur/measure/ui/CompassPage.kt
- com/vayunmathur/measure/ui/CompassContent.kt
- com/vayunmathur/measure/ui/LevelPage.kt
- com/vayunmathur/measure/ui/LevelContent.kt
- com/vayunmathur/measure/ui/RulerPage.kt
- com/vayunmathur/measure/ui/RulerContent.kt
- com/vayunmathur/measure/ui/SavedMeasurementsPage.kt
- com/vayunmathur/measure/ui/SavedMeasurementsContent.kt
- com/vayunmathur/measure/ui/DiagnosticsPage.kt
- com/vayunmathur/measure/ui/DiagnosticsContent.kt
- com/vayunmathur/measure/ui/SettingsPage.kt
- com/vayunmathur/measure/ui/SettingsContent.kt
- com/vayunmathur/measure/ui/components/MeasureBottomBar.kt
- com/vayunmathur/measure/ui/components/MeasureCamera.kt

## Verify (this module only)
```
./gradlew :measure:compileDevKotlin
./gradlew :measure:lint
./gradlew :measure:checkMetadata
```
