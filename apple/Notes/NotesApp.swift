// EverySync Notes — Phase 3 SwiftUI shell.
//
// ZERO sync logic in Swift. All blocks parsing (`parseNoteBody` /
// `serializeNoteBody` / `noteBodyText`), merge (`NoteKind`), transport
// (`IosRelayTransport`), and crypto lives in the shared XCFramework. This
// target renders the editor, maps PencilKit strokes to the shared ink model
// (see `PencilKitMapping.swift`), renders blocks natively (see
// `NoteBodyView.swift`), hosts the Share extension (see
// `ShareExtension.swift`), and schedules background sync. Notes soft-delete:
// tombstone rows persist locally and propagate
// (`NoteKind.localDeletesAreAuthoritative == true`).

import SwiftUI
import BackgroundTasks
import EverysyncCore

// MARK: - KMP entry points (Phase 3 wiring — call, never re-implement)
//
// data:        core.data.RemoteNote (uid / title / content / blocksJson /
//              position / deleted / updatedAt)
// blocks:      core.notes.parseNoteBody(blocksJson, fallbackContent)
//              core.notes.serializeNoteBody(body)
//              core.notes.noteBodyText(body) — plain-text projection for
//                search / preview / share. `ignoreUnknownKeys` keeps legacy
//                notes parsing.
//              core.notes.NoteBlock.Text(markdown, id) / .Image(fileName,
//                widthFraction, id) / .Ink(strokes, heightDp, id)
//              core.notes.InkStroke(brushFamily="pressurePen", brushSize,
//                brushColor argb int, brushEpsilon=0.1, points)
//              core.notes.InkPoint(x, y, timeMillis, pressure, tiltRadians,
//                orientationRadians)
// kind:        core.sync.adapters.NoteKind (syncId=uid, LWW updatedAt, hash
//              over canonical JSON with identity/position/timestamps stripped
//              — reorder is not an edit; `imageNames(blocksJson)` for blob
//              prefetch)
// blobs:       channel `notes-blobs:<vaultId>` for image bytes (never inline
//              in envelopes); sidecar via `../Shared/BlobStore.swift`
// driver:      core.provider.SyncDriver.drive(kind, store, remote, append, now)
// cursors:     core.platform.RelayCursorStore
// securestore: core.platform.SecureStore (Keychain actual)
// transport:   core.network.IosRelayTransport
//              (`recordChannel("notes", vaultId)` -> `notes:<vaultId>`)
// pqc/roles/links: same surface as Passwords; notes vaults may offer EDITOR
//              or VIEWER (unlike passwords — see `RelayRoles`).

@main
struct NotesApp: App {
    init() {
        // MAC-CHECK: identifier must match Info.plist
        // `BGTaskSchedulerPermittedIdentifiers`.
        BackgroundSync.register(handlers: [
            "com.vayunmathur.everysync.notes.refresh": { task in
                // TODO(Mac): call XCFramework SyncDriver cycle for each vault,
                // then re-schedule + `task.setTaskCompleted(success:)`.
                task.setTaskCompleted(success: true)
            },
        ])
    }

    var body: some Scene {
        WindowGroup {
            NotesRootView()
        }
    }
}

struct NotesRootView: View {
    // TODO(Mac): note list driven by SyncDriver snapshots; preview text via
    // `noteBodyText` in the XCFramework (never hand-parsed in Swift).
    // Editor binds PKCanvasView and maps PKStroke -> InkStroke per
    // `PencilKitMapping.swift`, then serializes via `serializeNoteBody`.
    @State private var query = ""

    var body: some View {
        NavigationStack {
            VStack {
                // TODO(Mac): replace with the XCFramework-driven list.
                Text("EverySync Notes")
                    .navigationTitle("Notes")
            }
            .searchable(text: $query)
        }
    }
}
