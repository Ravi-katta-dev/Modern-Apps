# AGENTS.md — games/logicgate/ (':games:logicgate')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `games/logicgate/` + allowed shared modules. Do not root-scan.

_Install: ./install games/logicgate (dev by default)._
<!-- last-reviewed: 2026-10-16 -->
- Gradle: :games:logicgate / dir: games/logicgate/
- Package roots present: ui, data, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: (none declared)
- Metadata: metadata_data/games-logicgate.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** Logic-gate circuit puzzle — drag-to-connect gates over progressive levels incl. a Turing-gate finale. 100% offline.
**Game shape:** `ui/` circuit canvas (gates/wires/backdrop/transform) + game/progression screens → `platform/LogicViewModel` → `data/` circuit models + chip library + levels.
**Entry points:** `Route` → `MainActivity.kt` → `Navigation.kt` → `ui/GameScreen.kt` → `platform/LogicViewModel.kt` → `data/CircuitModel.kt`.
**Gotchas:** Canvas gesture math (`CanvasTransform`) and eval (`GameEval`) are the bug surface — keep transform/eval pure and unit-tested.

## Key files
### Root — entry + navigation
- com/vayunmathur/games/logicgate/MainActivity.kt
- com/vayunmathur/games/logicgate/Navigation.kt
- com/vayunmathur/games/logicgate/Route.kt
### data/ — circuits, chips, levels
- com/vayunmathur/games/logicgate/data/CircuitModel.kt
- com/vayunmathur/games/logicgate/data/ChipLibrary.kt
- com/vayunmathur/games/logicgate/data/Levels.kt
- com/vayunmathur/games/logicgate/data/ProgressRepository.kt
### platform/ — ViewModel + achievements
- com/vayunmathur/games/logicgate/platform/LogicViewModel.kt
- com/vayunmathur/games/logicgate/platform/LogicUiContract.kt
- com/vayunmathur/games/logicgate/platform/Achievements.kt
- com/vayunmathur/games/logicgate/platform/AppBackupAgent.kt
### ui/ — canvas + game + progression
- com/vayunmathur/games/logicgate/ui/GameScreen.kt
- com/vayunmathur/games/logicgate/ui/GamePage.kt
- com/vayunmathur/games/logicgate/ui/CircuitCanvas.kt
- com/vayunmathur/games/logicgate/ui/CircuitGates.kt
- com/vayunmathur/games/logicgate/ui/CircuitWires.kt
- com/vayunmathur/games/logicgate/ui/CircuitBackdrop.kt
- com/vayunmathur/games/logicgate/ui/CanvasTransform.kt
- com/vayunmathur/games/logicgate/ui/GameEval.kt
- com/vayunmathur/games/logicgate/ui/GameInventory.kt
- com/vayunmathur/games/logicgate/ui/GameLabels.kt
- com/vayunmathur/games/logicgate/ui/GameLayout.kt
- com/vayunmathur/games/logicgate/ui/GamePanels.kt
- com/vayunmathur/games/logicgate/ui/MobileTokens.kt
- com/vayunmathur/games/logicgate/ui/ProgressionScreen.kt
- com/vayunmathur/games/logicgate/ui/ProgressionPage.kt
- com/vayunmathur/games/logicgate/ui/TuringGate.kt
- com/vayunmathur/games/logicgate/ui/TuringTerminal.kt
- com/vayunmathur/games/logicgate/ui/Theme.kt
- com/vayunmathur/games/logicgate/ui/components/BitDotsRow.kt

## Verify (this module only)
```
./gradlew :games:logicgate:compileDevKotlin
./gradlew :games:logicgate:lint
./gradlew :games:logicgate:checkMetadata
```
