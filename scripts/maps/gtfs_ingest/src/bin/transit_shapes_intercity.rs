//! Which rail lines are intercity, and the colour they draw in at country zoom.
//!
//! An intercity line is carried from z3 (`mamaps_build`'s `schema::transit`), where it is the
//! only transit drawn, so it has to be both correctly picked out and readable on its own.

use gtfs_ingest::shapes::distance_m;

/// A rail shape at least this long, end to end along its path, is intercity whatever its
/// `route_type`: Amtrak, VIA and Brightline all publish plain `2`. Commuter rail stays under it.
pub(crate) const INTERCITY_LENGTH_M: f64 = 150_000.0;

/// Relative luminance (0..1) above which an intercity line takes the train fallback colour.
/// Amtrak publishes `#CAE4F1` (about 0.74), which vanishes on the light basemap at country
/// zoom; VIA's `#FFCB06` (about 0.63) still reads and is kept.
const MAX_LUMINANCE: f64 = 0.7;

/// GTFS extended types that name long-distance rail outright: high speed, long distance,
/// inter-regional and sleeper.
pub(crate) fn is_long_distance_type(route_type: u32) -> bool {
    matches!(route_type, 101 | 102 | 103 | 105)
}

pub(crate) fn is_intercity(long_distance_type: bool, points: &[(i32, i32)]) -> bool {
    long_distance_type
        || points.windows(2).map(|w| distance_m(w[0], w[1])).sum::<f64>() >= INTERCITY_LENGTH_M
}

/// The colour a line is written with: its own, unless it is intercity and too pale to read.
pub(crate) fn drawn_color(color: u32, intercity: bool, fallback: u32) -> u32 {
    if intercity && luminance(color) > MAX_LUMINANCE { fallback } else { color }
}

fn luminance(color: u32) -> f64 {
    let channel = |shift: u32| {
        let c = f64::from((color >> shift) & 0xFF) / 255.0;
        if c <= 0.039_28 { c / 12.92 } else { ((c + 0.055) / 1.055).powf(2.4) }
    };
    0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn long_or_typed_rail_is_intercity() {
        // ~1.1 degrees of latitude per step: 2 steps is ~222 km.
        let long = [(370_000_000, -1_220_000_000), (380_000_000, -1_220_000_000), (390_000_000, -1_220_000_000)];
        let short = [(370_000_000, -1_220_000_000), (375_000_000, -1_220_000_000)];
        assert!(is_intercity(false, &long), "222 km of plain type-2 rail");
        assert!(!is_intercity(false, &short), "a 56 km commuter line");
        assert!(is_intercity(is_long_distance_type(102), &short), "type 102 is long distance");
        assert!(!is_long_distance_type(2) && !is_long_distance_type(109));
    }

    #[test]
    fn pale_intercity_colours_take_the_fallback() {
        assert_eq!(drawn_color(0xCAE4F1, true, 0x0057A8), 0x0057A8, "Amtrak's published colour");
        assert_eq!(drawn_color(0xFFCB06, true, 0x0057A8), 0xFFCB06, "VIA yellow reads");
        assert_eq!(drawn_color(0xCAE4F1, false, 0x0057A8), 0xCAE4F1, "only intercity lines change");
    }
}
