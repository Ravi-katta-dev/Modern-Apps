//! Wash-overzoom tests: inference, stretch, stitch, and convergence.
//!
//! The tiler's wash cap drops wash areas at z14; these tests pin the
//! renderer's half — deciding when to underlay the z13 parent
//! ([`needs_wash_underlay`]), stretching it into the child quadrant, and
//! stitching it into the child body ([`stitch_parent_wash`]) — plus the two
//! safety properties: old archives render exactly as before, and ocean stays
//! ocean.
use super::wash_overzoom::{
    needs_wash_underlay, parent_of, stitch_parent_wash, DETAIL_KINDS, WASH_CAP_ZOOM,
};
use crate::style;
use tilecodec::mamaps::body::{
    Body, Feature, Layer as BodyLayer, Part, GEOM_LINE, GEOM_POLYGON, NAME_NONE, WINDING_OUTER,
};
use tilecodec::mamaps::dict;

/// One full-tile NONE polygon: what a gated z14 tile's wash looked like before
/// the cap (and what the z13 parent still carries).
fn mainland() -> (BodyLayer, Vec<(i16, i16)>) {
    let mut layer = BodyLayer::new(dict::LAYER_LANDTYPE);
    layer.features.push(Feature {
        kind: dict::NONE,
        kind_detail: dict::NONE,
        geom_type: GEOM_POLYGON,
        flags: 0,
        name_idx: NAME_NONE,
        parts_offset: 0,
        part_count: 1,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 0,
    });
    layer.parts.push(Part { coord_start: 0, point_count: 4, winding: WINDING_OUTER });
    layer.coords = vec![(0, 0), (4096, 0), (4096, 4096), (0, 4096)];
    (layer, vec![(0, 0), (4096, 0), (4096, 4096), (0, 4096)])
}

/// A body whose landtype holds only the mainland wash (old-archive shape).
fn old_body() -> Body {
    let mut body = Body::new(4096);
    let (landtype, _) = mainland();
    body.layers.push(landtype);
    body
}

/// A body with no landtype at all (ocean, or gated z14 with wash dropped).
fn no_landtype_body() -> Body {
    Body::new(4096)
}

/// A body whose landtype holds one detail pool (minzoom-14 survivor).
fn detail_body() -> Body {
    let mut body = Body::new(4096);
    let mut landtype = BodyLayer::new(dict::LAYER_LANDTYPE);
    landtype.features.push(Feature {
        kind: style::kind_id_for_test("swimming_pool"),
        kind_detail: dict::NONE,
        geom_type: GEOM_POLYGON,
        flags: 0,
        name_idx: NAME_NONE,
        parts_offset: 0,
        part_count: 1,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 0,
    });
    landtype.parts.push(Part { coord_start: 0, point_count: 4, winding: WINDING_OUTER });
    landtype.coords = vec![(100, 100), (500, 100), (500, 500), (100, 500)];
    body.layers.push(landtype);
    body
}

/// A body whose landtype holds one river line (lines never count as wash).
fn lines_only_body() -> Body {
    let mut body = Body::new(4096);
    let mut landtype = BodyLayer::new(dict::LAYER_LANDTYPE);
    landtype.features.push(Feature {
        kind: style::kind_id_for_test("river"),
        kind_detail: dict::NONE,
        geom_type: GEOM_LINE,
        flags: 0,
        name_idx: NAME_NONE,
        parts_offset: 0,
        part_count: 1,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 0,
    });
    landtype.parts.push(Part { coord_start: 0, point_count: 2, winding: WINDING_OUTER });
    landtype.coords = vec![(0, 2000), (4096, 2100)];
    body.layers.push(landtype);
    body
}

#[test]
fn wash_present_renders_crisp_only() {
    // Old archives (wash in the tile) never underlay: behavior unchanged.
    assert!(!needs_wash_underlay(&old_body(), WASH_CAP_ZOOM));
}

#[test]
fn detail_only_lines_only_empty_and_absent_underlay() {
    assert!(needs_wash_underlay(&detail_body(), WASH_CAP_ZOOM));
    assert!(needs_wash_underlay(&lines_only_body(), WASH_CAP_ZOOM));
    let mut empty = Body::new(4096);
    empty.layers.push(BodyLayer::new(dict::LAYER_LANDTYPE));
    assert!(needs_wash_underlay(&empty, WASH_CAP_ZOOM));
    assert!(needs_wash_underlay(&no_landtype_body(), WASH_CAP_ZOOM));
}

#[test]
fn inference_is_zoom_gated() {
    // Below z14 nothing underlays, even with no landtype at all.
    assert!(!needs_wash_underlay(&no_landtype_body(), 13));
    assert!(!needs_wash_underlay(&detail_body(), 13));
    assert!(!needs_wash_underlay(&no_landtype_body(), 0));
}

#[test]
fn the_detail_list_matches_the_tiler_gate() {
    // Six street-level area kinds; every one must resolve through the real
    // dictionary, or the inference silently treats gated detail as wash.
    assert_eq!(DETAIL_KINDS.len(), 6);
    for name in DETAIL_KINDS {
        let id = style::kind_id_for_test(name);
        assert_ne!(id, dict::NONE, "{name} must be a real kind");
        let body = {
            let mut b = Body::new(4096);
            let mut l = BodyLayer::new(dict::LAYER_LANDTYPE);
            l.features.push(Feature {
                kind: id,
                kind_detail: dict::NONE,
                geom_type: GEOM_POLYGON,
                flags: 0,
                name_idx: NAME_NONE,
                parts_offset: 0,
                part_count: 1,
                transit_color: 0,
                transit_ordinal: 0,
                transit_lanes: 0,
                transit_taper: 0,
                lane_count: 0,
            });
            l.parts.push(Part { coord_start: 0, point_count: 4, winding: WINDING_OUTER });
            l.coords = vec![(0, 0), (100, 0), (100, 100), (0, 100)];
            b.layers.push(l);
            b
        };
        assert!(
            needs_wash_underlay(&body, WASH_CAP_ZOOM),
            "{name} must read as detail (underlay), not wash"
        );
    }
}

#[test]
fn parent_coords_and_quadrants() {
    assert_eq!(parent_of(0, 0), (0, 0, 0, 0));
    assert_eq!(parent_of(1, 0), (0, 0, 1, 0));
    assert_eq!(parent_of(0, 1), (0, 0, 0, 1));
    assert_eq!(parent_of(3, 5), (1, 2, 1, 1));
    assert_eq!(parent_of(16383, 16383), (8191, 8191, 1, 1));
}

#[test]
fn stitch_inserts_wash_as_first_layer_when_child_has_none() {
    let child = no_landtype_body();
    let parent = old_body();
    let stitched = stitch_parent_wash(&child, &parent, 0, 0);
    let layer = stitched.layer(dict::LAYER_LANDTYPE).expect("stitched wash");
    assert_eq!(layer.features.len(), 1);
    assert_eq!(layer.features[0].kind, dict::NONE);
    // Quadrant (0,0): parent units double in place.
    assert_eq!(layer.coords, vec![(0, 0), (8192, 0), (8192, 8192), (0, 8192)]);
    // id 0 sorts first: single layer, so trivially ordered.
    assert_eq!(stitched.layers[0].layer_id, dict::LAYER_LANDTYPE);
}

#[test]
fn stitch_offsets_into_the_child_quadrant() {
    let child = no_landtype_body();
    let parent = old_body();
    // Quadrant (1,1): child = parent * 2 - 4096.
    let stitched = stitch_parent_wash(&child, &parent, 1, 1);
    let layer = stitched.layer(dict::LAYER_LANDTYPE).expect("stitched wash");
    assert_eq!(layer.coords, vec![(-4096, -4096), (4096, -4096), (4096, 4096), (-4096, 4096)]);
}

#[test]
fn stitch_appends_behind_existing_detail() {
    let child = detail_body();
    let parent = old_body();
    let stitched = stitch_parent_wash(&child, &parent, 0, 1);
    let layer = stitched.layer(dict::LAYER_LANDTYPE).expect("merged landtype");
    // Detail pool first, stitched wash behind it.
    assert_eq!(layer.features.len(), 2);
    assert_eq!(layer.features[0].parts_offset, 0);
    assert_eq!(layer.features[1].parts_offset, 1);
    assert_eq!(layer.coords.len(), 8);
    // The wash parts start where the pool's arena ends.
    assert_eq!(layer.parts[1].coord_start, 4);
}

#[test]
fn stitch_skips_parent_lines() {
    let child = no_landtype_body();
    let mut parent = Body::new(4096);
    let mut landtype = BodyLayer::new(dict::LAYER_LANDTYPE);
    landtype.features.push(Feature {
        kind: style::kind_id_for_test("river"),
        kind_detail: dict::NONE,
        geom_type: GEOM_LINE,
        flags: 0,
        name_idx: NAME_NONE,
        parts_offset: 0,
        part_count: 1,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 0,
    });
    landtype.parts.push(Part { coord_start: 0, point_count: 2, winding: WINDING_OUTER });
    landtype.coords = vec![(0, 0), (4096, 4096)];
    parent.layers.push(landtype);
    let stitched = stitch_parent_wash(&child, &parent, 0, 0);
    // No areas stitched: the body is returned with no landtype layer, so the
    // caller keeps today's ocean path rather than building an empty wash.
    assert!(stitched.layer(dict::LAYER_LANDTYPE).is_none());
}

#[test]
fn stitch_remaps_parent_names_into_the_child_table() {
    let mut child = Body::new(4096);
    child.names.push("Child Road".to_string());
    let mut parent = Body::new(4096);
    parent.names.push("Lake Parent".to_string());
    let mut landtype = BodyLayer::new(dict::LAYER_LANDTYPE);
    landtype.features.push(Feature {
        kind: style::kind_id_for_test("lake"),
        kind_detail: dict::NONE,
        geom_type: GEOM_POLYGON,
        flags: 0,
        name_idx: 1,
        parts_offset: 0,
        part_count: 1,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 0,
    });
    landtype.parts.push(Part { coord_start: 0, point_count: 4, winding: WINDING_OUTER });
    landtype.coords = vec![(0, 0), (1000, 0), (1000, 1000), (0, 1000)];
    parent.layers.push(landtype);
    let stitched = stitch_parent_wash(&child, &parent, 0, 0);
    // Child's own name keeps index 1; the lake appends at 2.
    assert_eq!(stitched.names, vec!["Child Road".to_string(), "Lake Parent".to_string()]);
    let layer = stitched.layer(dict::LAYER_LANDTYPE).expect("stitched wash");
    assert_eq!(layer.features[0].name_idx, 2);
}

#[test]
fn stitch_with_parent_missing_landtype_returns_child() {
    let child = detail_body();
    let parent = no_landtype_body();
    let stitched = stitch_parent_wash(&child, &parent, 0, 0);
    // Unchanged: same features, same names. The caller keeps the ocean path
    // for the wash (converges correctly: z13 without wash is ocean).
    assert_eq!(stitched.layers.len(), child.layers.len());
    assert_eq!(stitched.names, child.names);
}

#[test]
fn stitched_bodies_build_without_panic() {
    // Stretched coordinates run past the child extent (negative, >4096 by
    // design). The tessellator must tolerate them — this is the shape every
    // marker tile will feed it. Assert the earth fill meshes in every
    // quadrant: the stitched wash is what it tessellates.
    use super::build::build;
    let layers = crate::style::layers();
    fn mesh_for<'a>(
        mesh: &'a super::mesh::TileMesh,
        layers: &'a [crate::style::Layer],
        id: &str,
    ) -> Option<&'a super::mesh::LayerMesh> {
        mesh.meshes.iter().find(|m| layers[m.layer_index].id == id)
    }
    let child = detail_body();
    let parent = old_body();
    for (qx, qy) in [(0, 0), (1, 0), (0, 1), (1, 1)] {
        let stitched = stitch_parent_wash(&child, &parent, qx, qy);
        let mesh = build(&stitched, layers, 14, 2 * qx + 1, 2 * qy + 1, true);
        assert!(
            mesh_for(&mesh, layers, "earth").is_some(),
            "quadrant ({qx},{qy}) tessellates the stitched wash"
        );
    }
}
