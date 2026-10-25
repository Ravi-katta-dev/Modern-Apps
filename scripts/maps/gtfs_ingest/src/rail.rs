//! Which GTFS routes and shapes the basemap's rail-line layer is drawn from.
//!
//! Shared by `transit_shapes`, which reads them out of raw feeds, and the pack builder, which
//! stores exactly those shapes in `world.transit`'s RAIL_LINES section so the layer can be
//! rebuilt from the pack alone. One definition, so the two cannot drift apart.

use crate::gtfs::Csv;
use std::collections::{BTreeMap, BTreeSet, HashMap};

/// One rail route: `(mode, colour, is a fallback colour, display name, route_type)`.
pub type RailRoute = (&'static str, u32, bool, String, u32);

/// GTFS `route_type` → the basemap's mode vocabulary, or `None` for a mode this
/// layer does not carry (bus, ferry, trolleybus, aerial lift, ...).
///
/// The extended ranges are here because European feeds use them almost
/// exclusively: a feed publishing its metro as 402 rather than 1 would otherwise
/// contribute nothing at all.
pub fn mode_of(route_type: u32) -> Option<&'static str> {
    Some(match route_type {
        // GTFS 0 is "tram, streetcar, light rail" in one value. `light_rail`
        // rather than `tram` because that is what North American agencies put
        // there — Muni Metro, MAX, Link — and its zoom floor is the shallower of
        // the two, so a street tram filed under 0 surfaces one zoom early rather
        // than a light rail line vanishing until z13.
        0 => "light_rail",
        1 => "subway",
        2 => "train",
        // Cable tram and funicular: short, street-level, and the tram zoom floor.
        5 | 7 => "tram",
        12 => "monorail",
        100..=117 => "train",
        400..=404 => "subway",
        405 => "monorail",
        900..=906 => "tram",
        _ => return None,
    })
}

/// The fallback line colour per mode, as `0xRRGGBB`, for a route naming none.
///
/// **A matched pair with `mamaps_build`'s `schema::transit::MODES`**: the five
/// names above and the five colours here are that table, duplicated because this
/// crate is deliberately detached from the Android workspace and has no
/// dependencies, so the two cannot share code. A change to either is a change to
/// both.
pub fn fallback_color(mode: &str) -> u32 {
    match mode {
        "subway" => 0xE4002B,
        "light_rail" => 0x00985F,
        "tram" => 0xFFD200,
        "train" => 0x0057A8,
        "monorail" => 0x9D9D9D,
        _ => 0x666666,
    }
}

/// A `route_color` cell as `0xRRGGBB`, or `None` to fall back per mode.
///
/// GTFS spells it as bare `RRGGBB`; a leading `#` is accepted because feeds put
/// one there anyway. Zero is refused rather than emitted: it means "no colour" on
/// the wire, so a route that really is black takes its mode's colour instead.
pub fn route_color(raw: &str) -> Option<u32> {
    let hex = raw.trim();
    let hex = hex.strip_prefix('#').unwrap_or(hex);
    if hex.len() != 6 || !hex.chars().all(|c| c.is_ascii_hexdigit()) {
        return None;
    }
    let value = u32::from_str_radix(hex, 16).ok()?;
    (value != 0).then_some(value)
}

/// `route_id` → [`RailRoute`] for every rail route in the feed. Everything else, bus
/// included, is dropped here.
pub fn rail_routes(routes: &Csv) -> HashMap<String, RailRoute> {
    let mut out = HashMap::new();
    for row in &routes.rows {
        let id = routes.get(row, "route_id");
        if id.is_empty() {
            continue;
        }
        let Ok(route_type) = routes.get(row, "route_type").trim().parse::<u32>() else { continue };
        let Some(mode) = mode_of(route_type) else { continue };
        let (color, fallback) = match route_color(routes.get(row, "route_color")) {
            Some(color) => (color, false),
            None => (fallback_color(mode), true),
        };
        let short = routes.get(row, "route_short_name").trim();
        let long = routes.get(row, "route_long_name").trim();
        let name = if short.is_empty() { long } else { short };
        out.insert(id.to_string(), (mode, color, fallback, name.to_string(), route_type));
    }
    out
}

/// The distinct `shape_id`s each rail route's trips reference.
///
/// One line per **distinct polyline**, not per trip and not per RAPTOR route
/// group: a route's trips reference many shapes (one per direction, one per
/// short-turn variant) and every trip carrying its own line would draw the same
/// alignment hundreds of times. `BTreeMap`/`BTreeSet` rather than the hashed
/// pair, because the output has to be byte-identical between runs.
pub fn shape_ids_by_route(
    trips: &Csv,
    rail: &HashMap<String, RailRoute>,
) -> BTreeMap<String, BTreeSet<String>> {
    let mut out: BTreeMap<String, BTreeSet<String>> = BTreeMap::new();
    for row in &trips.rows {
        let route_id = trips.get(row, "route_id");
        if !rail.contains_key(route_id) {
            continue;
        }
        let shape_id = trips.get(row, "shape_id").trim();
        if shape_id.is_empty() {
            continue;
        }
        out.entry(route_id.to_string()).or_default().insert(shape_id.to_string());
    }
    out
}
