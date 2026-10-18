// EverySync Notes — native NoteBody rendering.
//
// Renders the XCFramework's `NoteBody` blocks natively (text/markdown,
// blob-channel images by `fileName`, PencilKit ink). This file does NO
// parsing/serialization/hashing/merging: it takes an already-parsed `NoteBody`
// from the XCFramework (`parseNoteBody`) and binds edits back through
// `serializeNoteBody`. Swift never defines its own block schema.
//
// Wire shape (mirrored verbatim — do not extend in Swift):
//   {"blocks":[
//     {"type":"text","id":"…","markdown":"…"},
//     {"type":"image","id":"…","fileName":"<sha256>.jpg","widthFraction":1.0},
//     {"type":"ink","id":"…","strokes":[],"heightDp":300}]}
// `InkStroke`: `brushFamily` (default `"pressurePen"`), `brushSize`,
// `brushColor` (argb int), `brushEpsilon` (default `0.1`), `points`.
// `InkPoint`: `x, y, timeMillis, pressure, tiltRadians, orientationRadians`.
//
// MAC-CHECK (needs xcodebuild on a Mac):
// - `EverysyncCore` Kotlin class names below are the K/N export shape
//   (`NoteBody` / `NoteBlock.Text` / `.Image` / `.Ink`). The Mac must confirm
//   the exact names in the generated `EverysyncCore.h` and adjust call sites.

import SwiftUI
import EverysyncCore

/// Renders one note's blocks. The view model holds the XCFramework `NoteBody`
/// object; every edit (text change, image resize, new strokes) writes through
/// `serializeNoteBody` into the envelope IPC. Tombstoned notes (`deleted`)
/// render a "deleted" placeholder — Swift never raw-deletes.
struct NoteBodyView: View {
    // TODO(Mac): bind to the XCFramework NoteBody object.
    // Cases (from `NoteBlock` — exhaustive over Text/Image/Ink):
    // - Text: native TextEditor bound to `markdown`. Markdown rendering is
    //   display-only; the stored string stays verbatim (merge hashes it).
    // - Image: `fileName` resolved via BlobStore (see
    //   ../Shared/BlobStore.swift); prefetch the vault's set via
    //   `NoteKind.imageNames(blocksJson)`. `widthFraction` scales the image
    //   to that fraction of the editor width. Missing blob = placeholder
    //   (the hub is still fetching from `notes-blobs:<vaultId>`).
    // - Ink: PencilKit canvas (`PKCanvasView` wrapped in UIViewRepresentable)
    //   with `heightDp` height (default 300); strokes mapped via
    //   `PencilKitMapping.swift`. Canvas edits append/replace strokes in the
    //   XCFramework object — serialization stays in `serializeNoteBody`.
    var body: some View {
        Text("Note body renders here (Mac wires the XCFramework NoteBody)")
    }
}

/// Converts a `UIColor` to the shared-model argb int (`InkStroke.brushColor`).
/// Display helper only — the mapping itself lives in `PencilKitMapping.swift`.
enum InkColorHelp {
    static func argbInt(red: Double, green: Double, blue: Double, alpha: Double = 1.0) -> Int32 {
        let a = Int32((alpha * 255).rounded()) & 0xFF
        let r = Int32((red * 255).rounded()) & 0xFF
        let g = Int32((green * 255).rounded()) & 0xFF
        let b = Int32((blue * 255).rounded()) & 0xFF
        return (a << 24) | (r << 16) | (g << 8) | b
    }
}
