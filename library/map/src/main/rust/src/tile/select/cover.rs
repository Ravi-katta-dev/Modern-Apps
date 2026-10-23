//! Which resident tiles are fully covered by finer resident tiles.
//!
//! A tile is displayed at its own zoom *and* as a stand-in for up to
//! [`ANCESTOR_DEPTH`](super::resident::ANCESTOR_DEPTH) levels below it, and
//! already-resident descendants stand in on zoom-out — so the same ground is
//! routinely resident at several LODs at once, and the renderer draws every one
//! of them. At sea level (or pitch 0) the copies coincide pixel-exactly and the
//! overdraw is invisible; off sea level each LOD drapes the road from its *own*
//! DEM at its *own* normalisation, so the copies separate into stacked roads.
//!
//! A tile's draws are redundant only when every point of it is covered by a
//! *settled* finer tile carrying the *same content*: settled because a tile
//! mid-fade is the backdrop its ancestor fades over, and same-content because a
//! z15 tile carries no `landtype` wash — the z14 ancestor's earth fill must keep
//! drawing where the finer tiles have no earth of their own. Coverage is
//! all-or-nothing per tile: a partially covered ancestor keeps drawing
//! everywhere (brief overdraw, never a hole).
//!
//! [`redundant_tiles`] is generic over one content predicate so each draw group
//! (one flat layer, the carriageways, terrain, buildings, traffic, arrows) gets
//! its own answer from the same recursion.

use super::tile_id::TileId;
use std::collections::{HashMap, HashSet};

/// How many LOD levels below a tile coverage may be proven through.
///
/// A resident set spans at most [`ANCESTOR_DEPTH`](super::resident::ANCESTOR_DEPTH)
/// ancestors above the visible level plus
/// [`DESCENDANT_DEPTH`](super::resident::DESCENDANT_DEPTH) descendants below it,
/// so 8 leaves margin on both sides. Proving coverage deeper than this reads as
/// uncovered: the coarse tile keeps drawing (overdraw, never a hole), which is
/// the safe direction.
pub const COVER_MAX_DEPTH: u8 = 8;

/// Tiles whose draws for one content group are redundant: every quadrant of
/// each returned tile is covered by a settled finer resident carrying that
/// content.
///
/// * `resident` — keys of every tile on the GPU.
/// * `settled` — the subset whose LOD fade has completed. A tile mid-fade is
///   still the opaque backdrop the fade needs, so it neither covers for its
///   ancestors nor is itself suppressed.
/// * `relevant` — `resident` plus their ancestors (see
///   [`relevant_keys`]): the only keys the recursion may descend into, which
///   keeps a missing intermediate level from fanning out into thousands of
///   never-resident descendants.
/// * `has_content` — whether a resident tile carries this group's geometry
///   (a mesh with the layer index, a non-empty carriageway/traffic/arrow list,
///   a terrain/building mesh, ...).
///
/// Only resident keys are ever returned. Missing coverage reads as not
/// redundant, so erring here costs overdraw, never a hole.
pub fn redundant_tiles(
    resident: &HashSet<u64>,
    settled: &HashSet<u64>,
    relevant: &HashSet<u64>,
    has_content: &dyn Fn(u64) -> bool,
) -> HashSet<u64> {
    let mut memo: HashMap<u64, bool> = HashMap::new();
    let mut out = HashSet::new();
    for &key in resident {
        let tile = TileId::from_key(key);
        let Some(children) = children(tile) else {
            continue;
        };
        if children
            .iter()
            .all(|c| quadrant_covered(resident, settled, relevant, has_content, &mut memo, *c, COVER_MAX_DEPTH))
        {
            let _inserted = out.insert(key);
        }
    }
    out
}

/// The resident keys plus their ancestors up to [`COVER_MAX_DEPTH`] levels:
/// every key the coverage recursion may visit.
///
/// A covering node is always a resident (the base case below requires it) or
/// an intermediate on the path to one, so anything outside this set covers
/// nothing and the recursion need not visit it.
pub fn relevant_keys(resident: &HashSet<u64>) -> HashSet<u64> {
    let mut relevant = resident.clone();
    for &key in resident {
        let mut tile = TileId::from_key(key);
        for _ in 0..COVER_MAX_DEPTH {
            let Some(parent) = tile.ancestor(1) else {
                break;
            };
            let _inserted = relevant.insert(parent.key());
            tile = parent;
        }
    }
    relevant
}

/// Whether `tile`'s whole quadrant is covered: a settled resident carrying the
/// content covers it directly, otherwise all four of its children must.
/// `depth` bounds the descent; unresolvable quadrants read as uncovered.
fn quadrant_covered(
    resident: &HashSet<u64>,
    settled: &HashSet<u64>,
    relevant: &HashSet<u64>,
    has_content: &dyn Fn(u64) -> bool,
    memo: &mut HashMap<u64, bool>,
    tile: TileId,
    depth: u8,
) -> bool {
    let key = tile.key();
    if let Some(&cached) = memo.get(&key) {
        return cached;
    }
    // Outside the relevant set nothing can cover: not resident, and no resident
    // descendant within reach. Checked before the resident test so a mangled or
    // far key fails fast rather than fanning out.
    if !relevant.contains(&key) {
        let _inserted = memo.insert(key, false);
        return false;
    }
    let covered = (resident.contains(&key) && settled.contains(&key) && has_content(key))
        || (depth > 0
            && children(tile).is_some_and(|kids| {
                kids.iter().all(|c| {
                    quadrant_covered(resident, settled, relevant, has_content, memo, *c, depth - 1)
                })
            }));
    let _inserted = memo.insert(key, covered);
    covered
}

/// The tile's four children one LOD down, or `None` when the keys would lie.
///
/// `x`/`y` double every level down, so past the packing width
/// ([`TileId::key`]) the arithmetic is still finite but the keys alias other
/// tiles — and an aliased key could read as resident, settled and contentful
/// when none of it is true, suppressing a tile nothing covers. The round-trip
/// rejects any child whose key does not decode back to itself, which makes a
/// false-covered tile unrepresentable whatever depths ever become resident.
fn children(tile: TileId) -> Option<[TileId; 4]> {
    let z = tile.z.checked_add(1)?;
    let x = tile.x.checked_mul(2)?;
    let y = tile.y.checked_mul(2)?;
    // `x + 1`/`y + 1` by checked add: real tile coordinates stay far below
    // `u32::MAX`, but a plain `+` would wrap in release past it — and a
    // wrapped key could alias a resident tile and suppress one nothing covers.
    let (x1, y1) = (x.checked_add(1)?, y.checked_add(1)?);
    let kids = [
        TileId { z, x, y },
        TileId { z, x: x1, y },
        TileId { z, x, y: y1 },
        TileId { z, x: x1, y: y1 },
    ];
    // Anything past the packing width fails the round-trip: the key aliases
    // some other tile, and an aliased key could read as resident, settled and
    // contentful when none of it is true — suppressing a tile nothing covers.
    if kids.iter().all(|k| TileId::from_key(k.key()) == *k) {
        Some(kids)
    } else {
        None
    }
}
