// EverySync Notes — PencilKit <-> shared ink-model mapping.
//
// PencilKit has no shared counterpart, so the stroke converter lives here,
// but it maps 1:1 onto `InkPoint`/`InkStroke` and performs NO merge / hash /
// crypto. Serialization of the mapped strokes (`serializeNoteBody`) stays in
// the XCFramework. Canvas height maps to the ink block's `heightDp`
// (default 300).
//
// Mapping (verbatim from `Notes/README.md`):
// | `PKStrokePoint`          | `InkPoint`            |
// | `location.x / .y`        | `x / y`               |
// | `timeOffset + stroke start` | `timeMillis`       |
// | `force` (fallback `size`)| `pressure`            |
// | `pi/2 - altitude`        | `tiltRadians`         |
// | `azimuth`                | `orientationRadians` |
// | `PKStroke` / `PKInk`     | `InkStroke`           |
// | `ink.inkType`            | `brushFamily`         |
// | `ink` line width         | `brushSize`           |
// | `ink.color` -> argb int  | `brushColor`          |
// | constant                 | `brushEpsilon = 0.1`  |
//
// MAC-CHECK (needs xcodebuild on a Mac):
// - `PKStrokePoint` property names (`location`, `timeOffset`, `size`,
//   `force`, `azimuth`, `altitude`) against the linked iOS SDK.
// - `PKInk.InkType` -> `brushFamily` string mapping: confirm the exact raw
//   strings the Mac wants (`"pressurePen"` default; pen/pencil/marker map to
//   documented family names — keep the mapping table in ONE place, here).

import PencilKit
import UIKit
import EverysyncCore

/// Thin platform layer: PencilKit in, shared-model strokes out (and back for
/// rendering stored ink onto the canvas). No logic beyond unit conversion.
public enum PencilKitMapping {
    /// Default when the ink type has no family mapping.
    public static let defaultBrushFamily = "pressurePen"
    public static let defaultBrushEpsilon: Float = 0.1

    // TODO(Mac): implement against the generated XCFramework `InkStroke` /
    // `InkPoint` Kotlin types:
    //
    // static func inkStroke(from stroke: PKStroke, startMillis: Int64) -> InkStroke:
    //   - brushFamily = familyName(for: stroke.ink.inkType) ?? defaultBrushFamily
    //   - brushSize = Float(stroke.ink.lineWidth(for: ...)) (line-width API
    //     varies by SDK — confirm on the Mac; store the rendered width)
    //   - brushColor = argb int of stroke.ink.color (see InkColorHelp)
    //   - brushEpsilon = 0.1 (constant — matches the shared default)
    //   - points = stroke.path.map { inkPoint(from: $0, startMillis:) }
    //
    // static func inkPoint(from point: PKStrokePoint, startMillis: Int64) -> InkPoint:
    //   - x = Float(point.location.x), y = Float(point.location.y)
    //   - timeMillis = startMillis + Int64((point.timeOffset * 1000).rounded())
    //   - pressure = Float(point.size.width > 0 ? point.force : fallback)
    //     (PencilKit reports force 0 for finger touches — fall back to 1.0 so
    //     strokes stay visible; the fallback is display-only, stored verbatim)
    //   - tiltRadians = Float(.pi / 2 - point.altitude)
    //   - orientationRadians = Float(point.azimuth)
    //
    // static func pkStroke(from ink: InkStroke) -> PKStroke (render path):
    //   - rebuild PKStrokePath from points; PKInk with the mapped type/width/color.
    //   - Lossy where PencilKit has no equivalent (epsilon, exact family) —
    //     acceptable: the stored InkStroke is source of truth, the PKStroke is
    //     a render projection only.

    /// Maps a PencilKit ink type to the shared `brushFamily` string.
    /// TODO(Mac): confirm exact family strings; keep `"pressurePen"` default.
    public static func familyName(for inkType: PKInk.InkType) -> String {
        switch inkType {
        case .pen: return "pressurePen"
        case .pencil: return "pencil"
        case .marker: return "marker"
        default: return defaultBrushFamily
        }
    }
}
