# AGENTS.md — todo/ (':todo')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `todo/` + allowed shared modules. Do not root-scan.

_Install: ./install todo (dev by default)._
<!-- last-reviewed: 2026-10-16 -->
- Gradle: :todo / dir: todo/
- Package roots present: ui, data, platform, sync
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :library:room
- Metadata: metadata_data/todo.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** Offline task list with due dates, drag reorder, backup/restore, and E2EE sync via EverySync (`sync/RelayTodoProvider`).
**Key features:**
- Tasks with due dates, check-off, drag-to-reorder list + detail screens
- Local backup/restore via `AppBackupAgent`; relay sync snapshots consumed by everysync (`TodoSink`/`TodoIpcSink` side)
- `TodoUiContract` + `TodoViewModel` over Room (`Todo`, `TodoDatabase`, `TodoRepository`)
**Architecture / data-flow:** `ui/` list/detail → `platform/TodoViewModel` → `data/TodoRepository` over Room; `sync/RelayTodoProvider` exposes the relay surface everysync syncs.
**Entry points + reading order:** `Route` → `MainActivity.kt` → `Navigation.kt` → `ui/TodoListScreen.kt` → `platform/TodoViewModel.kt` → `data/TodoRepository.kt` → `sync/RelayTodoProvider.kt`.
**Gotchas:**
- `settings.gradle.kts` lists `:todo` twice — harmless Gradle quirk, indexed once everywhere
- Relay schema changes must stay compatible with everysync's `TodoKind` adapter — update both sides together

## Key files
### Root — entry + navigation
- com/vayunmathur/todo/MainActivity.kt
- com/vayunmathur/todo/Navigation.kt
- com/vayunmathur/todo/Route.kt
### data/ — Room store
- com/vayunmathur/todo/data/TodoDatabase.kt
- com/vayunmathur/todo/data/TodoRepository.kt
- com/vayunmathur/todo/data/Todo.kt
### platform/ — ViewModel, contract, backup
- com/vayunmathur/todo/platform/TodoViewModel.kt
- com/vayunmathur/todo/platform/TodoUiContract.kt
- com/vayunmathur/todo/platform/AppBackupAgent.kt
### sync/ — everysync relay surface
- com/vayunmathur/todo/sync/RelayTodoProvider.kt
### ui/ — list + detail
- com/vayunmathur/todo/ui/TodoListScreen.kt
- com/vayunmathur/todo/ui/TodoListPage.kt
- com/vayunmathur/todo/ui/TodoDetailScreen.kt
- com/vayunmathur/todo/ui/TodoDetailPage.kt

## Verify (this module only)
```
./gradlew :todo:compileDevKotlin
./gradlew :todo:lint
./gradlew :todo:checkMetadata
```
