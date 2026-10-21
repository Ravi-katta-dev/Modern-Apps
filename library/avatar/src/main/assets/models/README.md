# models/

Place the authored `base_upperbody.glb` here. Requirements (see `../../AGENTS.md`
"Base mesh asset"):

- ~13–15 joints named per `joint_name()` (`root/spine/chest/neck/head`,
  `clavicle_L/R`, `upperArm_L/R`, `forearm_L/R`, `hand_L/R`)
- `JOINTS_0` / `WEIGHTS_0` 4-weight skinning on the first mesh primitive
- Distinct `glasses` / `beard` / hair nodes for attachments
- 2K albedo honoring the skin `[0,.25]` / shirt `[.5,1]` UV split
- ≤65536 verts (tighter cap for grid cells)

The `.glb` ships uncompressed (`noCompress` in `build.gradle.kts`) and is
handed to native as a detached fd by `YuuAvatarAssets`. Until it lands, the
engine renders the procedural fallback mesh.

## Packed-mesh node contract (v1)

Mesh selection keys off node names — these spellings are the contract, used
verbatim (no case/format variants). `hairStyle` / `glassesStyle` /
`beardStyle` from `YuuFaceConfig` each select exactly one node per group per
frame; assembly = base body + selected attachments.

- Hair: `hair_00`, `hair_01`, … `hair_11` — 12 nodes, ALL real, no hidden slot.
  `hairStyle` 0..11 (`HAIR_STYLES = 12`) maps 1:1 onto `hair_00`..`hair_11`.
- Glasses: `glasses_01` … `glasses_06` — 6 nodes. Style 0 = hidden (editor
  shows "None" at index 0); styles 1..6 map onto `glasses_01`..`glasses_06`.
- Beard: `beard_01` … `beard_05` — 5 nodes. Style 0 = hidden; styles 1..5 map
  onto `beard_01`..`beard_05`.

Attachment rules:

- Every attachment parents to the Head joint (`head` per `joint_name()`).
- Skinning: parts that deform carry `JOINTS_0` / `WEIGHTS_0` 4-weight skinning
  (weights normalized, remapped onto the UBO joint order); everything else is
  rigid, bound 100% to Head — joints `[head, 0, 0, 0]`, weights `[1, 0, 0, 0]`,
  the same convention as the procedural face decal quads in `mesh.rs`.
- One 2K albedo for the pack, honoring the skin `[0, .25]` / shirt `[.5, 1]`
  UV split above (face decal UVs live in the atlas face block instead — see
  next section).
- Budget: ≤65536 verts total assembled (base + selected attachments), per
  `mesh_budgets()`.

## Face texture atlas layout (v1)

Matches `atlas.rs` exactly (`ATLAS_SIZE = 256`; face block `[0, 128, 256, 256]`
= bottom half; skin-tone base fill everywhere so legacy skin UVs keep working):

- Grid: `FACE_COLS = 12` x `FACE_ROWS = 4`. One cell = 256/12 x 128/4 px.
- Rows: 0 = eyes, 1 = brows, 2 = nose, 3 = mouth (`face_cell_uv(row, style)`;
  row clamped to 0..3, column = `style mod 12`).
- Overlays: mole / wrinkle / blush share the SPARE BROW-ROW cells (row 1,
  cols 8..11) via `overlay_cell_uv(style)` = row 1, col `8 + (style mod 4)` —
  exactly the cells `generate_atlas` paints, so decal UVs and painted pixels
  always agree.
- Only the SELECTED style cell per row is painted; unselected columns stay
  skin. Colors (8 skin, 8 hair/brow, 5 eye, 4 mouth) are ARGB tints applied at
  paint time, not separate atlas regions.
- The head face quad (`mesh.rs`: `FACE_Z = 0.145`, just proud of the skull
  front at z = 0.14 so it never z-fights) carries 4 decal bands
  (eyes / brows / nose / mouth) bound 100% to Head. Slider traits
  (`eyeSpacing` / `eyeHeight` / `eyeSize`, `browHeight`, `noseHeight`,
  `mouthSize` / `mouthHeight`) shift the band rects; style traits pick the
  cell. Nonzero mole appends 1 cheek quad, wrinkle 1 forehead quad, blush 2
  cheek quads — all UV'd at the matching overlay cell.

## v1 pack manifest (data)

`base_upperbody.glb` ships:

- Mesh nodes: `hair_00`..`hair_11` (12), `glasses_01`..`glasses_06` (6),
  `beard_01`..`beard_05` (5). Base body always assembled; one node per group
  selected per frame (glasses/beard style 0 assembles none).
- Face regions: atlas rows for 12 eye / 8 brow / 8 nose / 12 mouth styles,
  plus overlay cells (row 1, cols 8..11) for 4 mole / 4 wrinkle / 3 blush
  styles.
- Face shape (8) is procedural — head-bone scale, no pack content.
- Colors are palette tints, not pack content.

### Const-vs-pack audit (`YuuFaceConfig` is source of truth — consts NOT changed)

| Const | Value | Pack / atlas reality | Verdict |
|---|---|---|---|
| `FACE_SHAPES` | 8 | Procedural head-bone scale; no nodes or atlas cells | OK (pack-agnostic) |
| `HAIR_STYLES` | 12 | `hair_00`..`hair_11`, all real | MATCH |
| `EYE_STYLES` | 12 | Row 0, cols 0..11 | MATCH |
| `BROW_STYLES` | 8 | Row 1, cols 0..7 painted/selected | MISMATCH — atlas has 12 cols; cols 8..11 are overlay cells, never brow-selectable |
| `NOSE_STYLES` | 8 | Row 2, cols 0..7 painted/selected | MISMATCH — cols 8..11 exist but are never painted or selected |
| `MOUTH_STYLES` | 12 | Row 3, cols 0..11 | MATCH |
| `GLASSES_STYLES` | 6 | `glasses_01`..`glasses_06`; 0 = hidden | MATCH |
| `BEARD_STYLES` | 5 | `beard_01`..`beard_05`; 0 = hidden | MATCH |
| `MOLE_STYLES` | 4 | Styles 1..4 -> cols 9, 10, 11, 8; 0 = none | OK (note: style 4 wraps onto col 8) |
| `WRINKLE_STYLES` | 4 | Styles 1..4 -> cols 9, 10, 11, 8; 0 = none | OK (same wrap note) |
| `BLUSH_STYLES` | 3 | Styles 1..3 -> cols 9, 10, 11; 0 = none | OK (note: col 8 never used by blush) |

Further mismatches / hazards (report only, no const changed):

1. Brow-row sharing: brow styles occupy row-1 cols 0..7 while mole/wrinkle/
   blush overlays occupy cols 8..11 — no conflict today, but bumping
   `BROW_STYLES` past 8 would paint/select over the overlay cells (native
   mods style by 12). That bump needs an atlas rework, not just editor
   pagination.
2. Stale comment in `atlas.rs`: `generate_atlas` docs say overlays paint on
   "mouth-row spare cells", but the code and `overlay_cell_uv` both use the
   brow row (row 1). Code is source of truth.
3. Native indexing is lenient (`style mod 12`, negatives clamped to 0); the
   real range gate is the editor `VariantGrid` counts — face parts `0..N-1`,
   extras `0..N` with 0 = "None".

## Outfit / body-type / accessory extension path (recipe — implement nothing)

To add a new axis (e.g. `outfitStyle`):

1. `YuuFaceConfig.kt`: add the field (e.g. `outfitStyle: Int = 0`) plus a
   `XXX_STYLES` count const. Count rule: bare count if 0 is a real variant
   (hair/eyes pattern); count + 1 grid with index 0 as "None" if 0 means
   hidden (glasses/beard/extras pattern).
2. `YuuEditorScreen.kt` `YuuDraft`: mirror the field — constructor param,
   `mutableIntStateOf`, plus `toConfig()` and `from()` arms.
3. `YuuEditorContract.kt` `YuuCategory`: add the entry (rail order = enum
   order).
4. `YuuEditorScreen.kt`: add an `XxxPanel(draft)` composable (`VariantGrid` +
   `SectionLabel`), a `YuuCategory.Xxx -> XxxPanel(draft)` branch, a
   `labelRes()` arm (`R.string.yuu_cat_xxx`), and a preview-stepper arm if the
   axis has sliders. Give its `VariantGrid` cells a fresh
   `YuuSurfaceIds.forVariant(panelBase, …)` base (Extras uses 6..10).
5. Strings: add `yuu_cat_xxx` (plus section labels) to hub strings; "None"
   reuses the shared `UiR.string.none`.
6. Native: extend `YuuTraits` plus the node-select / atlas-row plumbing in the
   Rust engine when mesh/art exists. New mesh nodes follow this README's
   naming rules (`outfit_00..`, style-0 semantics declared up front).

## No `.glb` in this change

This change is contract + manifest only — no `base_upperbody.glb` is added.
Art authors against the node contract (§ Packed-mesh node contract) and atlas
layout above separately; until the `.glb` lands, `YuuAvatarAssets` keeps the
best-effort handoff and the engine renders the procedural fallback mesh (face
decals included).
