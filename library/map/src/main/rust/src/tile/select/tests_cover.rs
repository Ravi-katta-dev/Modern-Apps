//! Covered-stand-in suppression: a redundant tile draws nothing twice.
//!
//! Split from `tests_part3.rs` (file-length limit). These pin the contract the
//! renderer relies on in
//! [`refresh_suppression`](crate::vulkan::renderer::Renderer::refresh_suppression):
//! a settled finer set covering a tile suppresses it, anything less does not,
//! and every failure direction errs toward drawing (overdraw, never a hole).
use super::*;
use std::collections::HashSet;

fn keys(tiles: &[(u8, u32, u32)]) -> HashSet<u64> {
    tiles
        .iter()
        .map(|&(z, x, y)| TileId { z, x, y }.key())
        .collect()
}

/// All four children of z10 5/5, plus z10 5/5 itself: the full stand-in stack.
fn full_stack() -> (HashSet<u64>, HashSet<u64>) {
    let resident = keys(&[
        (10, 5, 5),
        (11, 10, 10),
        (11, 11, 10),
        (11, 10, 11),
        (11, 11, 11),
    ]);
    let relevant = relevant_keys(&resident);
    (resident, relevant)
}

#[test]
fn a_tile_covered_by_four_settled_children_is_redundant() {
    let (resident, relevant) = full_stack();
    let settled = resident.clone();
    let redundant = redundant_tiles(&resident, &settled, &relevant, &|_| true);
    let coarse = TileId { z: 10, x: 5, y: 5 }.key();
    assert!(
        redundant.contains(&coarse),
        "the coarse tile draws nothing its children do not"
    );
    // The children cover nothing — they are the finest level, so nothing is
    // finer than them and they always draw.
    for &(z, x, y) in &[(11, 10, 10), (11, 11, 10), (11, 10, 11), (11, 11, 11)] {
        assert!(
            !redundant.contains(&TileId { z, x, y }.key()),
            "a finest-level tile must always draw"
        );
    }
}

#[test]
fn one_missing_child_keeps_the_ancestor_drawing() {
    let resident = keys(&[
        (10, 5, 5),
        (11, 10, 10),
        (11, 11, 10),
        (11, 10, 11),
        // (11, 11, 11) still in flight: a quarter of the tile is uncovered.
    ]);
    let relevant = relevant_keys(&resident);
    let settled = resident.clone();
    let redundant = redundant_tiles(&resident, &settled, &relevant, &|_| true);
    assert!(
        !redundant.contains(&TileId { z: 10, x: 5, y: 5 }.key()),
        "a quarter of the tile is uncovered — the ancestor must keep drawing"
    );
}

#[test]
fn a_child_mid_fade_covers_nothing_and_is_itself_kept() {
    let (resident, relevant) = full_stack();
    // The (11, 11, 11) tile landed this frame: still fading in over its
    // ancestor, so it is not settled. Its quadrant reads as uncovered, which
    // keeps the ancestor drawing — and the fading tile itself is never
    // suppressed, because it is the backdrop the fade needs.
    let settled: HashSet<u64> = resident
        .iter()
        .copied()
        .filter(|key| *key != TileId { z: 11, x: 11, y: 11 }.key())
        .collect();
    let redundant = redundant_tiles(&resident, &settled, &relevant, &|_| true);
    assert!(
        !redundant.contains(&TileId { z: 10, x: 5, y: 5 }.key()),
        "a quadrant is still fading in — the ancestor stays"
    );
    assert!(
        !redundant.contains(&TileId { z: 11, x: 11, y: 11 }.key()),
        "a tile mid-fade is never suppressed"
    );
}

#[test]
fn coverage_without_the_content_suppresses_nothing() {
    // The z14-wash shape: four settled z15 children cover the tile, but they
    // carry roads while the suppressed group is the earth fill they do not
    // have. The ancestor's wash must keep drawing.
    let (resident, relevant) = full_stack();
    let settled = resident.clone();
    let coarse = TileId { z: 10, x: 5, y: 5 }.key();
    let redundant = redundant_tiles(&resident, &settled, &relevant, &|key| key != coarse);
    assert!(
        redundant.contains(&coarse),
        "covering tiles carry the content here, so the ancestor is redundant"
    );
    // Now nothing carries the content: coverage without content suppresses
    // nothing.
    let no_content = redundant_tiles(&resident, &settled, &relevant, &|_| false);
    assert!(
        no_content.is_empty(),
        "no tile carries the content — nothing may be suppressed"
    );
}

#[test]
fn coverage_reaches_through_a_missing_level() {
    // Zoom-out descendants: z12 tiles resident under a z10 ancestor with no
    // z11 in between. Each z11 quadrant is proven through its own four z12
    // children.
    let mut tiles = vec![(10, 5, 5)];
    for dx in 0..4 {
        for dy in 0..4 {
            tiles.push((12, 20 + dx, 20 + dy));
        }
    }
    let resident = keys(&tiles);
    let relevant = relevant_keys(&resident);
    let settled = resident.clone();
    let redundant = redundant_tiles(&resident, &settled, &relevant, &|_| true);
    assert!(
        redundant.contains(&TileId { z: 10, x: 5, y: 5 }.key()),
        "sixteen z12 tiles cover the z10 tile through the missing z11"
    );
}

#[test]
fn a_lone_tile_is_never_redundant() {
    let resident = keys(&[(10, 5, 5)]);
    let relevant = relevant_keys(&resident);
    let settled = resident.clone();
    assert!(
        redundant_tiles(&resident, &settled, &relevant, &|_| true).is_empty(),
        "nothing covers it — it must draw"
    );
}

#[test]
fn a_coarser_tile_never_covers_a_finer_one() {
    // Ancestors accumulate in the resident set; coverage only flows downward.
    let resident = keys(&[(10, 5, 5), (9, 2, 2)]);
    let relevant = relevant_keys(&resident);
    let settled = resident.clone();
    let redundant = redundant_tiles(&resident, &settled, &relevant, &|_| true);
    assert!(
        !redundant.contains(&TileId { z: 10, x: 5, y: 5 }.key()),
        "the z9 ancestor does not cover the z10 tile"
    );
    assert!(
        !redundant.contains(&TileId { z: 9, x: 2, y: 2 }.key()),
        "the z10 tile covers one quadrant of z9, but three are bare — \
         the z9 tile must keep drawing too"
    );
}

#[test]
fn deep_keys_that_would_alias_never_suppress() {
    // Past the packing width the child keys alias other tiles. The round-trip
    // rejects them, so coverage fails closed: the tile keeps drawing.
    let tile = TileId {
        z: 22,
        x: (1 << 22) - 1,
        y: (1 << 22) - 1,
    };
    let resident = keys(&[(22, tile.x, tile.y)]);
    let relevant = relevant_keys(&resident);
    let settled = resident.clone();
    assert!(
        redundant_tiles(&resident, &settled, &relevant, &|_| true).is_empty(),
        "unresolvable children read as uncovered, never as covered"
    );
}

#[test]
fn relevant_keys_stay_bounded() {
    // The recursion may only visit residents and their near ancestors: one
    // tile contributes at most COVER_MAX_DEPTH + 1 keys, so a stranger key
    // planted in `relevant` is the only way coverage could consult it.
    let resident = keys(&[(14, 100, 200)]);
    let relevant = relevant_keys(&resident);
    assert!(
        relevant.len() <= COVER_MAX_DEPTH as usize + 1,
        "one tile's ancestry is a chain, not a fan-out: {}",
        relevant.len()
    );
    assert!(
        relevant.contains(&TileId { z: 14, x: 100, y: 200 }.key()),
        "the resident itself is relevant"
    );
}
