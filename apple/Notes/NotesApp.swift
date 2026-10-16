// EverySync Notes — Phase 3a SwiftUI shell.
//
// Zero sync logic in Swift. All blocks parsing, merge, transport, and crypto
// lives in the shared XCFramework (everysync-core KMP). This file only
// declares the UI entry point and — in comments — the exact KMP entry points
// Phase 3 will call, plus the PencilKit mapping contract (see README.md).
// Nothing here parses JSON, merges, hashes, or encrypts.

import SwiftUI
import PencilKit
import EverysyncCore

// MARK: - KMP entry points (Phase 3 wiring, for reference only)
//
// data:        com.vayunmathur.everysync.core.data.RemoteNote
// blocks:      com.vayunmathur.everysync.core.notes.parseNoteBody
//              com.vayunmathur.everysync.core.notes.serializeNoteBody
//              com.vayunmathur.everysync.core.notes.noteBodyText
//              com.vayunmathur.everysync.core.notes.NoteBlock
//              com.vayunmathur.everysync.core.notes.InkStroke
//              com.vayunmathur.everysync.core.notes.InkPoint
// kind:        com.vayunmathur.everysync.core.sync.adapters.NoteKind
// blobs:       NoteKind.imageNames(blocksJson); channel "notes-blobs:<vaultId>"
// driver:      com.vayunmathur.everysync.core.provider.SyncDriver
// cursors:     com.vayunmathur.everysync.core.platform.RelayCursorStore
// securestore: com.vayunmathur.everysync.core.platform.SecureStore (Keychain actual)
// transport:   com.vayunmathur.everysync.core.network.IosRelayTransport
// channels:    recordChannel("notes", vaultId) -> "notes:<vaultId>"

@main
struct NotesApp: App {
    // TODO(Phase 3): create SyncDriver + IosRelayTransport from the
    //  XCFramework; register BGTaskScheduler handlers (see README.md).
    var body: some Scene {
        WindowGroup {
            NotesRootView()
        }
    }
}

struct NotesRootView: View {
    // TODO(Phase 3): note list driven by SyncDriver snapshots; editor binds
    //  PKCanvasView and maps PKStroke -> InkStroke per README mapping table,
    //  then serializes via serializeNoteBody in the XCFramework.
    var body: some View {
        Text("EverySync Notes")
    }
}
