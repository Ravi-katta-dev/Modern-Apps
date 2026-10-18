# Apple envelope IPC contract (Phase 3a)

How the Passwords / Notes / Hub processes share vault envelopes on-device.
Mirrors the Android relay-provider ops and the KMP `SyncSink` interface
(`querySince` / `upsertRaw` / `deleteByVault`) with **identical columns**.
The hub is the only process that talks to the relay; shells read and write
envelopes here; the Credential Provider extension reads over XPC.

## Transport

- **Location:** App-Group shared container
  `group.<team>.everysync.shared`, one SQLite file per vault at
  `vault/<vaultId>/envelopes.sqlite`, plus a `blobs/` sidecar dir for note
  image bytes keyed by `fileName`.
- **Coordination:** `NSFileCoordinator` for file-level coordination between
  the apps and their extensions.
- **XPC for the Credential Provider:** the autofill extension cannot assume
  direct store access, so the containing Passwords app exposes an XPC service
  (`com.vayunmathur.everysync.envelope-ipc`) with exactly the three ops below.
  The extension never touches the relay.

## Ops (map 1:1 to KMP `SyncSink`)

| IPC op | KMP `SyncSink` | Semantics |
|---|---|---|
| `querySince(vaultId, table, since)` | `listLocal` + `localChanges` | Rows with `updatedAt > since`, tombstones included; drives both baseline reads and delta detection. An unhealthy read aborts the merge (never fold failure as empty). |
| `upsertRaw(vaultId, table, rows)` | `upsert` | Idempotent upsert keyed on `syncId`; preserves pulled timestamps. |
| `deleteByVault(vaultId, table, syncId)` | `delete` | Passwords table: **hard-delete** the row. Notes/todos tables: set `deleted=1` tombstone. Merge-driven deletes ALWAYS arrive here as tombstone envelopes, never raw deletes. |

## Columns (identical everywhere)

| Column | Type | Semantics | KMP source |
|---|---|---|---|
| `syncId` | TEXT PRIMARY KEY | Relay sync identity (opaque 32-char id) | `EntityKind.syncId` → `RemoteX.uid` |
| `updatedAt` | INTEGER | Last modification, epoch ms; drives relay LWW | `RemoteX.updatedAt` |
| `deleted` | INTEGER 0/1 | Tombstone flag | `RemoteX.deleted` |
| `payloadJson` | TEXT | Full `RemoteX` JSON envelope, plaintext at rest (protected by sandbox + Data Protection; encryption happens at the relay layer, not here) | `PasswordKind` / `NoteKind` / `TodoKind.toJson` |
| `contentHash` | TEXT | `relayContentHash(canonical + "\0deleted=…")`; canonical strips identity/ordering/timestamps | `EntityKind.contentHash` |

## Tables ↔ relay channels

| Table | Relay channel | Envelope | Delete |
|---|---|---|---|
| `passwords` | `passwords:<vaultId>` | `RemotePassword` (`isPasskey` selects the password vs passkey half) | **HARD** — rows vanish; only the app-side snapshot remembers them (`localDeletesAreAuthoritative == false`, so a healthy-read absence IS a genuine local delete) |
| `notes` | `notes:<vaultId>` | `RemoteNote` (title + content + `blocksJson`; image bytes in `blobs/` + `notes-blobs:<vaultId>`) | **SOFT** — `deleted=1` tombstones persist and propagate |
| `todos` | `todos:<vaultId>` | `RemoteTodo` | **SOFT** — tombstones persist and propagate |
| `contacts` / `calendars` | per `recordChannel` | `RemoteContact` / `RemoteEvent` | Hub-managed; same columns; delete semantics per the XCFramework entity kind (Swift holds no opinion) |

Contacts/calendar rows are hub-internal (system stores are the UI); shells
for those types do not exist in Phase 3a.

## Appendix — Phase 3 conformance (no drift)

Reviewed against the Phase 3 Swift (`Shared/EnvelopeIPC.swift`,
`Shared/BlobStore.swift`, `Passwords/PasswordsXPCService.swift`,
`Hub/IPCRelayStore.swift`, `Hub/SyncIdMap.swift`) — contract holds verbatim:

- 3 ops (`querySince` / `upsertRaw` / `deleteByVault`) with identical
  columns (`syncId / updatedAt / deleted / payloadJson / contentHash`).
- 5 tables (`passwords` HARD / `notes`, `todos` SOFT / hub-internal
  `contacts`, `calendars` with kind-driven delete semantics).
- App-Group container + `NSFileCoordinator` + XPC (exactly the 3 ops;
  extension never touches the relay); `blobs/` sidecar keyed by `fileName`.
- Cursor keys in Keychain `SecureStore`, never in these tables; vault keys
  never appear here or in share links.
- WAL + `busy_timeout=5000` so the Kotlin `RelayStore` shims (specified in
  `Hub/IPCRelayStore.swift`, owned by `XCFramework/IOS_ACTUALS_NOTES.md`)
  share the same files; schema v1.

Build-note drift (NOT contract drift): `XCFramework/BUILD_NOTES.md` uses the
`aarch64-apple-ios-sim` Rust target for Apple-Silicon simulators where
`library/everysync-core/IOS_PQC_NOTES.md` says `x86_64-apple-ios`
(Intel-only). Relay/channel/table semantics are unaffected.

## Concurrency rules

1. Single writer to the relay: the hub. Shells only touch the IPC store.
2. All IPC access goes through `NSFileCoordinator` (or the XPC service,
   which does it internally).
3. Cursor keys (`relay_cursor:<type>:<vault>:<channel>`) live in the Keychain
   `SecureStore` via `RelayCursorStore`, not in these tables.
4. Vault AES keys never appear here or in share links — keys move exclusively
   via PQC-sealed inbox invites (`inbox:<deviceId>`).
