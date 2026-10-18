//! P5 kinds-aware re-tessellation: the stamp a build records.
//!
//! Split from `tests.rs` (file-length limit): `build_toggled` stamps every mesh with
//! the toggle snapshot it saw plus what its source could have emitted
//! ([`ToggleStamp`](crate::style::ToggleStamp)), so `has_tile` can skip the rebuilds
//! a toggle change cannot affect. These pin the stamp and the verdicts that follow
//! from it; the predicate's own matrix lives in `style::filter`'s `stamp_tests`.
use super::build::build_toggled;
use crate::style;
use crate::style::{KindFilter, LayerToggles};
use tilecodec::mamaps::body::{
    Body, Feature, Layer as BodyLayer, Part, GEOM_LINE, GEOM_POINT, NAME_NONE, WINDING_OUTER,
};
use tilecodec::mamaps::dict;

/// The tile holds two POI points (cafe, bar) and one rail line. Built with POI on, the
/// stamp carries both kinds; narrowing the chips to cafe then changes the admitted set
/// (stale), while re-selecting both in any order does not (fresh). The rail line marks
/// `has_transit` even though transit is off — admissibility is toggle-independent.
#[test]
fn build_toggled_stamps_the_snapshot_and_gated_summary() {
    let cafe = crate::style::kind_id_for_test("cafe");
    let bar = crate::style::kind_id_for_test("bar");
    let rail = crate::style::kind_id_for_test("rail");

    let mut body = Body::new(4096);
    body.names.push("Cafe Epo".to_string());
    let mut poi = BodyLayer::new(dict::LAYER_POI);
    for (kind, x) in [(cafe, 1000i16), (bar, 3000)] {
        poi.features.push(Feature {
            kind,
            kind_detail: dict::NONE,
            geom_type: GEOM_POINT,
            flags: 0,
            name_idx: 0,
            parts_offset: poi.parts.len() as u32,
            part_count: 1,
            transit_color: 0,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
            lane_count: 0,
        });
        poi.parts.push(Part {
            coord_start: poi.coords.len() as u32,
            point_count: 1,
            winding: WINDING_OUTER,
        });
        poi.coords.push((x, 2000));
    }
    body.layers.push(poi);
    let mut transit = BodyLayer::new(dict::LAYER_TRANSIT);
    transit.features.push(Feature {
        kind: rail,
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
    transit.parts.push(Part {
        coord_start: 0,
        point_count: 2,
        winding: WINDING_OUTER,
    });
    transit.coords.extend_from_slice(&[(100, 100), (3900, 3900)]);
    body.layers.push(transit);

    let layers = style::layers();
    let toggles = LayerToggles {
        poi: true,
        transit: false,
        traffic: false,
    };
    let mesh = build_toggled(&body, layers, 14, 0, 0, false, toggles, &KindFilter::all(), 9);
    assert_eq!(mesh.generation, 9, "the generation fast path is unchanged");
    assert_eq!(mesh.toggle_stamp.toggles, toggles);
    assert!(mesh.toggle_stamp.kinds.is_empty());
    let mut summary = mesh.toggle_stamp.gated.poi_kinds.clone();
    summary.sort_unstable();
    assert_eq!(summary, vec![bar.min(cafe), bar.max(cafe)]);
    assert!(
        mesh.toggle_stamp.gated.has_transit,
        "admissibility ignores the toggle gate"
    );
    assert!(!mesh.toggle_stamp.gated.has_traffic);

    // Narrowing the chips drops bar: stale. Re-selecting both (any order): fresh.
    assert!(mesh
        .toggle_stamp
        .stale_under(&toggles, &KindFilter::new(vec![cafe])));
    assert!(!mesh
        .toggle_stamp
        .stale_under(&toggles, &KindFilter::new(vec![bar, cafe])));
    // Turning POI off removes two shown labels: stale.
    let off = LayerToggles {
        poi: false,
        ..toggles
    };
    assert!(mesh.toggle_stamp.stale_under(&off, &KindFilter::all()));
    // Transit is off and the tile holds rail: turning it on adds a line.
    let transit_on = LayerToggles {
        transit: true,
        ..toggles
    };
    assert!(mesh
        .toggle_stamp
        .stale_under(&transit_on, &KindFilter::all()));

    // Built with POI off: the same summary (the scan runs before the gate), no labels,
    // and a chip change is fresh — POI layers are gated regardless of the filter.
    let dark = build_toggled(&body, layers, 14, 0, 0, false, off, &KindFilter::all(), 10);
    assert_eq!(dark.toggle_stamp.gated.poi_kinds.len(), 2);
    assert!(!dark
        .toggle_stamp
        .stale_under(&off, &KindFilter::new(vec![cafe])));
    // But turning POI on adds the two points: stale.
    assert!(dark.toggle_stamp.stale_under(&toggles, &KindFilter::all()));
}
