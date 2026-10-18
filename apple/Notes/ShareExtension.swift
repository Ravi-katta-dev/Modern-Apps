// EverySync Notes — Share extension.
//
// `NSExtension` (`com.apple.share-services`) target. Receives text / URLs /
// images, converts to `NoteBlock`s via the XCFramework parsers
// (`parseNoteBody` / text-block construction — never hand-built JSON), and
// writes through the App-Group envelope IPC. It NEVER talks to the relay
// directly; the hub picks up the new envelopes on its next cycle.
//
// Conversion (via XCFramework only):
// - text / URL -> `NoteBlock.Text(markdown:)` (URLs stored as markdown links;
//   Swift does not invent block types).
// - image -> write bytes to `BlobStore` under the XCFramework-derived
//   `fileName`, then `NoteBlock.Image(fileName:, widthFraction: 1.0)`.
// - New note = fresh syncId (`newRelaySyncId` in the XCFramework);
//   `blocksJson` = `serializeNoteBody`; row written via
//   `upsertRaw(vaultId:table:rows:)` on the `notes` table.
//
// MAC-CHECK (needs xcodebuild on a Mac):
// - Extension target links `EverysyncCore.xcframework` + shares the App Group.
// - `NSExtensionAttributes` (`NSExtensionActivationRule`) in the extension's
//   Info.plist — plain-text + URL + image predicates.

import UIKit
import Social
import EverysyncCore

/// Share-extension view controller. Thin adapter: system share sheet in,
/// envelope IPC out, XCFramework parsers in between.
final class EverySyncShareExtension: SLComposeServiceViewController {
    // TODO(Mac): read `extensionContext?.inputItems`; for each:
    // - text/URL: build NoteBlock.Text via the XCFramework (never raw JSON).
    // - image: persist bytes through BlobStore, build NoteBlock.Image.
    // Then serialize via `serializeNoteBody`, wrap in a RemoteNote envelope
    // via `NoteKind.toJson`, and `upsertRaw` into the active vault's `notes`
    // table. Surface errors with `AppMessages`-equivalent UI (no Toast —
    // repo-wide ban; use the host sheet's own presentation).
}
