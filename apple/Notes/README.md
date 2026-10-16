# EverySync Notes — Apple shell (Phase 3a)

SwiftUI shell. **All merge / transport / crypto lives in the shared
XCFramework** (`everysync-core` KMP); this target renders the editor, maps
PencilKit strokes to the shared ink model, hosts the Share extension, and
schedules background sync. Zero sync logic in Swift.

## Architecture

```
NotesApp (SwiftUI, PencilKit canvas) ──► EverysyncCore.xcframework ──► relay
        │                                      │
        │ render + thin PencilKit mapping      │ NoteBody parse/serialize,
        │                                      │ merge, transport, cursors
        ▼                                      ▼
Keychain (SecureStore)                  App-Group envelope IPC
BGTaskScheduler periodic                (see ../IPC_CONTRACT.md)
Share extension                         Blob store (image bytes by fileName)
```

## KMP entry points this shell will call (nothing else)

| Kotlin FQN | Used for |
|---|---|
| `core.data.RemoteNote` | Envelope: `uid / title / content / blocksJson / position / deleted / updatedAt`. |
| `core.notes.parseNoteBody` / `serializeNoteBody` / `noteBodyText` | Blocks JSON ⇄ editor model; plain-text projection for search/preview/share. `ignoreUnknownKeys` keeps legacy notes parsing. |
| `core.notes.NoteBlock` (`Text`/`Image`/`Ink`), `InkStroke`, `InkPoint` | Canonical model. Swift never defines its own block schema. |
| `core.sync.adapters.NoteKind` | `syncId=uid`, LWW on `updatedAt`, hash over canonical JSON (identity/position/timestamps stripped; reorder is not an edit), `imageNames(blocksJson)` for blob prefetch. |
| `core.provider.SyncDriver` + `core.platform.RelayCursorStore` | Sync cycles; cursor keys `relay_cursor:<type>:<vault>:<channel>`. |
| `core.network` | `IosRelayTransport`; record channel `notes:<vaultId>` via `recordChannel`; blob channel `notes-blobs:<vaultId>` for image bytes (never inline in envelopes). |
| PQC / roles / links | Same surface as Passwords (see `../Passwords/README.md`); notes vaults may offer EDITOR or VIEWER. |

## Wire shape (mirrored verbatim — do not extend in Swift)

```json
{"blocks":[
  {"type":"text","id":"…","markdown":"…"},
  {"type":"image","id":"…","fileName":"<sha256>.jpg","widthFraction":1.0},
  {"type":"ink","id":"…","strokes":[],"heightDp":300}
]}
```

`InkStroke`: `brushFamily` (default `"pressurePen"`), `brushSize`, `brushColor`
(argb int), `brushEpsilon` (default `0.1`), `points: InkPoint[]`.
`InkPoint`: `x, y, timeMillis, pressure, tiltRadians, orientationRadians`.

## PencilKit → InkPoint mapping (thin platform layer, in Swift)

PencilKit has no shared counterpart, so the stroke converter lives here, but
it maps 1:1 onto `InkPoint`/`InkStroke` and performs no merge/hash/crypto:

| `PKStrokePoint` | `InkPoint` |
|---|---|
| `location.x / .y` | `x / y` |
| `timeOffset + stroke start` | `timeMillis` |
| `force` (fallback `size`) | `pressure` |
| `π/2 − altitude` | `tiltRadians` |
| `azimuth` | `orientationRadians` |

| `PKStroke` / `PKInk` | `InkStroke` |
|---|---|
| `ink.inkType` | `brushFamily` |
| `ink` line width | `brushSize` |
| `ink.color` → argb int | `brushColor` |
| constant | `brushEpsilon = 0.1` |

Serialization of the mapped strokes (`serializeNoteBody`) stays in the
XCFramework. Canvas height maps to the ink block's `heightDp` (default 300).

## Images

Image bytes ride the blob channel keyed by `fileName`; envelopes carry only
the name + `widthFraction`. Prefetch via `NoteKind.imageNames(blocksJson)`.

## Delete semantics — SOFT delete

Notes soft-delete: tombstone rows (`deleted=true`) persist locally and
propagate. `NoteKind.localDeletesAreAuthoritative == true`.
`SyncSink.delete` tombstones; never raw-deletes. See `../IPC_CONTRACT.md`.

## Share extension

Share-extension target receives text/URLs/images, converts to `NoteBlock`s via
the XCFramework parsers, and writes through the App-Group envelope IPC. It
never talks to the relay directly.

## Keychain / BGTaskScheduler

Same pattern as Passwords: KMP `SecureStore` actual = Keychain
`kSecClassGenericPassword`; `BGAppRefreshTaskRequest(identifier:
"com.vayunmathur.everysync.notes.refresh")` for periodic pulls, one-shot
processing task after local edits. Scheduling only.

## Info.plist / entitlements notes

- `com.apple.security.application-groups`: `group.<team>.everysync.shared` (envelope IPC + blob store).
- `keychain-access-groups`: `$(AppIdentifierPrefix)com.vayunmathur.everysync.notes`.
- `NSExtension` (`com.apple.share-services`) on the Share-extension target, sharing the App Group.
- No camera/photo-library keys in this shell (image blocks reference shared-store files; capture UI adds its own usage strings later).

## Swift must NOT

Parse/serialize blocks JSON by hand, compute content hashes, run merge/LWW,
encrypt envelopes, or invent block types. All of that is `NoteBlocks.kt` +
`NoteKind` in the XCFramework.
