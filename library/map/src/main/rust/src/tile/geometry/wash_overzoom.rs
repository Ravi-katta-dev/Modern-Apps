//! Per-layer wash overzoom: stitch the z13 parent's wash under a z14 tile.
//!
//! The tiler's wash cap (`MAMAPS_WASH_CAP`) drops wash areas at z14 — every
//! `landtype` area whose class is carried at shallower zooms — while keeping
//! street-level detail (areas with `min_zoom == 14`: pools, pitches,
//! playgrounds) and all lines. A z14 tile's own `landtype` layer is therefore
//! detail-only or absent, and the wash has to come from the z13 parent.
//!
//! # Inference, not markers
//!
//! No format bit announces a gated tile — all 8 body flag bits are taken, and
//! none is needed. [`needs_wash_underlay`] keys off wash presence: a z14
//! `landtype` layer containing any area feature whose kind is NOT in
//! [`DETAIL_KINDS`] renders crisp only (old archives always take this path —
//! their wash is in the tile — so behavior there is unchanged). Anything else
//! at z14 (detail-only, lines-only, empty, or absent layer) underlays the
//! parent wash. Keying off wash presence (rather than detail absence) is what
//! makes this forward- and backward-safe: a kind neither side knows is either
//! in the tile (rendered crisp, correct) or it is not (underlaid, correct).
//!
//! [`DETAIL_KINDS`] mirrors the tiler gate (`is_wash_dropped` keeps areas with
//! `class.min_zoom == DEFAULT_MAX_ZOOM`): the six street-level area kinds. Kept
//! as names, resolved through the archive dictionary like the water list in
//! `super::terrain` — no hard-coded ids to drift when the dictionary grows. If
//! the schema adds a seventh minzoom-14 kind, both lists must grow together
//! (the coordinated release covers this; a mismatch degrades to crisp-only,
//! never to wrong).
//!
//! # Stitch-before-build
//!
//! [`stitch_parent_wash`] clones the child body and inserts the parent's wash
//! AREAS (never lines — the child carries its own lines; stitching the
//! parent's would double-label rivers) stretched 2x into the child's quadrant,
//! remapping names into the child's table. `build` then runs unchanged on the
//! augmented body: style windows see z14 content, the DEM mask runs on
//! stretched rings, and no `build` signature changes.
//!
//! Stretch is exact integer arithmetic (`child = parent * 2 - quadrant * 4096`)
//! — no rounding, so sibling tiles stitching one parent agree bit-for-bit on
//! shared edges. Parent coordinates stay inside `i16` throughout
//! (0..4096 doubled, minus 0/4096 → -4096..8192).
//!
//! Parent-missing or parent-without-landtype converges to today's ocean path:
//! the child is returned unchanged, and the existing absent-means-sea behavior
//! holds.

use tilecodec::mamaps::body::{Body, Layer, Part, GEOM_POLYGON, NAME_NONE};
use tilecodec::mamaps::dict::{self, LAYER_LANDTYPE};

/// The z14 wash cap applies at this zoom and fetches wash from one level up.
pub const WASH_CAP_ZOOM: u8 = 14;

/// Street-level `landtype` area kinds: the only areas the wash-capped tiler
/// keeps at z14. Everything else area-shaped at z14 is wash the parent owns.
///
/// Must match the tiler gate (`min_zoom == 14` areas in
/// `mamaps_build::schema::landtype::LANDUSE`): swimming_pool, dog_park,
/// playground, pitch, kindergarten, platform.
pub const DETAIL_KINDS: [&str; 6] = [
    "swimming_pool",
    "dog_park",
    "playground",
    "pitch",
    "kindergarten",
    "platform",
];

/// Whether a `landtype` kind id names a detail kind.
///
/// `NONE` (0) is wash — the land-base the cap drops — so it reads `false`. An
/// id past the dictionary also reads `false`: an unknown kind cannot be
/// detail the gate kept, so treating it as wash (crisp-only) is the safe side.
fn is_detail_kind(kind: u16) -> bool {
    kind != dict::NONE
        && dict::KINDS
            .get(kind as usize - 1)
            .is_some_and(|name| DETAIL_KINDS.contains(name))
}

/// Whether this z14 tile needs its wash underlaid from the z13 parent.
///
/// True when the tile's `landtype` layer holds no wash area: absent layer,
/// empty layer, or areas all in [`DETAIL_KINDS`] (lines never count either
/// way — a lone river neither proves wash present nor needs underlay
/// suppression). A layer containing any non-detail area renders crisp only,
/// which is what keeps old archives unchanged in behavior.
pub fn needs_wash_underlay(body: &Body, z: u8) -> bool {
    if z != WASH_CAP_ZOOM {
        return false;
    }
    let Some(layer) = body.layer(LAYER_LANDTYPE) else {
        return true;
    };
    !layer
        .features
        .iter()
        .any(|f| f.geom_type == GEOM_POLYGON && !is_detail_kind(f.kind))
}

/// The z13 parent tile coords covering a z14 child, plus the child's quadrant.
pub fn parent_of(x: u32, y: u32) -> (u32, u32, u32, u32) {
    (x >> 1, y >> 1, x & 1, y & 1)
}

/// Stretch one parent-unit coordinate into the child's quadrant, in child units.
fn stretch(v: i16, q: u32) -> i16 {
    (v as i32 * 2 - q as i32 * 4096) as i16
}

/// Intern `name` into `names` in first-use order, returning its 1-based index.
///
/// The body's own convention: `names[i - 1]` is the text for index `i`.
/// Appending only, so the child's own indices survive untouched.
fn intern(names: &mut Vec<String>, name: &str) -> u16 {
    if let Some(at) = names.iter().position(|n| n == name) {
        return at as u16 + 1;
    }
    names.push(name.to_string());
    names.len() as u16
}

/// Clone `child` with the parent body's wash AREAS stitched in, stretched 2x
/// into quadrant (`qx`, `qy`).
///
/// Parent lines are skipped (the child carries its own lines). Parent area
/// names (lakes, parks) remap into the child's table by appending — existing
/// indices untouched, order deterministic (parent feature order). The stitched
/// wash merges into the child's existing landtype layer when it has one
/// (feature `parts_offset` rebased onto the grown part table, coordinates
/// appended behind the child's arena), or becomes its own layer at the sorted
/// position for id 0 (`LAYER_LANDTYPE` sorts first) when it does not — either
/// way `Body::layer` finds one layer.
///
/// Returns the child unchanged when the parent has no landtype layer or no
/// stitchable areas (the caller then keeps today's ocean path).
pub fn stitch_parent_wash(child: &Body, parent: &Body, qx: u32, qy: u32) -> Body {
    let Some(parent_layer) = parent.layer(LAYER_LANDTYPE) else {
        return child.clone();
    };
    let mut out = child.clone();
    let mut stitched_features: Vec<tilecodec::mamaps::body::Feature> = Vec::new();
    let mut stitched_parts: Vec<Part> = Vec::new();
    let mut stitched_coords: Vec<(i16, i16)> = Vec::new();
    let mut names = out.names.clone();
    // Parent name_idx -> child name_idx for the names this stitch uses.
    let mut name_remap: std::collections::HashMap<u16, u16> = std::collections::HashMap::new();
    for feature in &parent_layer.features {
        if feature.geom_type != GEOM_POLYGON {
            continue;
        }
        let mut kept_parts: Vec<(Vec<(i16, i16)>, u16)> = Vec::new();
        for part in parent_layer.parts_of(feature) {
            let pts: Vec<(i16, i16)> = parent_layer
                .points(part)
                .iter()
                .map(|&(x, y)| (stretch(x, qx), stretch(y, qy)))
                .collect();
            if pts.len() >= 2 {
                kept_parts.push((pts, part.winding));
            }
        }
        if kept_parts.is_empty() {
            continue;
        }
        let mut stitched = *feature;
        if stitched.name_idx != NAME_NONE {
            let idx = *name_remap.entry(stitched.name_idx).or_insert_with(|| {
                parent
                    .names
                    .get(stitched.name_idx as usize - 1)
                    .map(|s| intern(&mut names, s))
                    .unwrap_or(NAME_NONE)
            });
            stitched.name_idx = idx;
        }
        stitched.parts_offset = stitched_parts.len() as u32;
        stitched.part_count = kept_parts.len() as u32;
        for (pts, winding) in kept_parts {
            stitched_parts.push(Part {
                coord_start: stitched_coords.len() as u32,
                point_count: pts.len() as u32,
                winding,
            });
            stitched_coords.extend_from_slice(&pts);
        }
        stitched_features.push(stitched);
    }
    if stitched_features.is_empty() {
        return out;
    }
    out.names = names;
    match out.layers.iter().position(|l| l.layer_id == LAYER_LANDTYPE) {
        Some(at) => {
            let layer = &mut out.layers[at];
            let parts_base = layer.parts.len() as u32;
            let coords_base = layer.coords.len() as u32;
            for part in &stitched_parts {
                layer.parts.push(Part {
                    coord_start: part.coord_start + coords_base,
                    point_count: part.point_count,
                    winding: part.winding,
                });
            }
            layer.coords.extend_from_slice(&stitched_coords);
            for mut f in stitched_features {
                f.parts_offset += parts_base;
                layer.features.push(f);
            }
        }
        None => {
            let mut layer = Layer::new(LAYER_LANDTYPE);
            layer.features = stitched_features;
            layer.parts = stitched_parts;
            layer.coords = stitched_coords;
            let at = out
                .layers
                .iter()
                .position(|l| l.layer_id > LAYER_LANDTYPE)
                .unwrap_or(out.layers.len());
            out.layers.insert(at, layer);
        }
    }
    out
}
