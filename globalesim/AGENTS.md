# AGENTS.md — globalesim/ (':globalesim')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `globalesim/` + allowed shared modules. Do not root-scan.

_Install: ./install globalesim (dev by default)._
<!-- last-reviewed: 2026-10-24 -->
- Gradle: :globalesim / dir: globalesim/
- Package roots present: ui, data, domain, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :library:network, :library:ui (via conventions), androidx.datastore.preferences, stripe-android
- Metadata: metadata_data/globalesim.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** Travel data eSIM storefront. One global eSIM per anonymous account — top up USD credit, data deducts at the local per-country rate. Credit never expires.
**Key features:**
- Anonymous account (client-minted UUIDv4, no email/PII) with transfer codes for new phones
- Rate catalog, wallet top-ups (budget pool → on-SIM wallet), Stripe card purchases
- One-tap install handoff to the euicc LPA (`EsimLink.provisioningUri` pinned to LPA package)
**Architecture / data-flow:** `ui/` screens → `platform/EsimViewModel` (single `EsimUiState`) → `data/api/EsimApi` (own `/esim/v1/*` on api.maproj.org, FIRST_PARTY) with pure `domain/QuoteMath` and `data/AnonymousIdStore` (DataStore UUID). The reseller vendor is server-only (`location_share_server/src/handlers/esim*.rs`) and never named here.
**Entry points + reading order:** `Route` (`Home` / `Rates` / `Install` / `Transfer` / `Budget`) → `MainActivity.kt` → `Navigation.kt` → `platform/EsimViewModel.kt` → `data/api/EsimApi.kt`.
**Gotchas:**
- Top-up buttons disable while loading; each attempt carries a fresh idempotency key, server dedupes replays
- PaymentSheet presents from `pendingClientSecret` via LaunchedEffect; cleared on done/cancel
- Install shows no QR/code — same-phone QR is unscannable; direct LPA handoff only
- `rememberPaymentSheet` is deprecated in stripe-android 23.x (same as fooddelivery); leave until repo migrates

## Key files
### Root — entry + navigation
- com/vayunmathur/globalesim/MainActivity.kt
- com/vayunmathur/globalesim/Navigation.kt
- com/vayunmathur/globalesim/Route.kt
### data/ — api + anonymous id
- com/vayunmathur/globalesim/data/api/EsimApi.kt — BASE_URL + url(path) + NetworkClient calls
- com/vayunmathur/globalesim/data/api/EsimDtos.kt — generic DTOs only, no vendor strings
- com/vayunmathur/globalesim/data/AnonymousIdStore.kt
### domain/ — pure math (no Android)
- com/vayunmathur/globalesim/domain/QuoteMath.kt
### platform/ — ViewModel
- com/vayunmathur/globalesim/platform/EsimViewModel.kt
### ui/ — one public @Composable per file
- com/vayunmathur/globalesim/ui/HomeScreen.kt
- com/vayunmathur/globalesim/ui/RatesScreen.kt
- com/vayunmathur/globalesim/ui/InstallScreen.kt
- com/vayunmathur/globalesim/ui/TransferScreen.kt
- com/vayunmathur/globalesim/ui/BudgetScreen.kt

## Verify (this module only)
```
./gradlew :globalesim:compileDevKotlin
./gradlew :globalesim:lint
./gradlew :globalesim:testDevUnitTest
```
