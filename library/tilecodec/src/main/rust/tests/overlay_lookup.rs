//! Phase 3 overlay lookup: integration gate for `mamaps::read::overlay`.
//!
//! Builds base + segment archives through the public writer API only, then
//! asserts patch-first semantics through the public [`TileSource`] seam:
//! segment hits win, tombstones answer absent, misses fall to the base, and
//! the base file's bytes are never consulted for a tombstoned tile.

use std::sync::Arc;

use tilecodec::mamaps::body::{
    Body, Feature, Layer, Part, DEFAULT_EXTENT, GEOM_LINE, NAME_NONE, WINDING_OUTER,
};
use tilecodec::mamaps::dict;
use tilecodec::mamaps::read::{LeafCache, MamapsArchive, OverlayArchive, TileSource};
use tilecodec::mamaps::write::{Options, StreamWriter};
use tilecodec::pmtiles::{tile_id, tile_zxy};
use tilecodec::proto::Result;
use tilecodec::stream::RangeReader;

/// A [`RangeReader`] over bytes in memory.
struct Memory {
    bytes: Vec<u8>,
}

impl RangeReader for Memory {
    fn read(&self, offset: u64, length: u32) -> Result<Vec<u8>> {
        if offset >= self.bytes.len() as u64 {
            return Ok(Vec::new());
        }
        let end = (offset + length as u64).min(self.bytes.len() as u64);
        Ok(self.bytes[offset as usize..end as usize].to_vec())
    }
}

/// A one-line body whose contents depend on `seed`.
fn body_for(seed: i16) -> Body {
    let mut roads = Layer::new(dict::LAYER_ROADS);
    roads.features.push(Feature {
        kind: 45,
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
    roads.parts.push(Part { coord_start: 0, point_count: 2, winding: WINDING_OUTER });
    roads.coords = vec![(0, 0), (seed, seed)];
    Body {
        extent: DEFAULT_EXTENT,
        layers: vec![roads],
        names: Vec::new(),
        ids: Vec::new(),
        turn_lanes: Vec::new(),
        buildings: Vec::new(),
        heightmap: None,
        carriageways: Vec::new(),
        convention: None,
        region_links: Vec::new(),
    }
}

/// An archive of `(tile_id, seed)` tiles, ascending as the writer requires.
fn archive(rows: &[(u64, i16)]) -> Vec<u8> {
    let mut sorted = rows.to_vec();
    sorted.sort_by_key(|(id, _)| *id);
    let mut writer = StreamWriter::new(Options::default()).expect("options");
    for (id, seed) in sorted {
        writer.append(id, &body_for(seed)).expect("append");
    }
    writer.finish().expect("finish")
}

fn open(bytes: Vec<u8>) -> MamapsArchive<Memory> {
    // Its own leaf cache: offsets collide across archives sharing one.
    MamapsArchive::open_with_cache(Memory { bytes }, Arc::new(LeafCache::new())).expect("open")
}

fn zxy(id: u64) -> (u8, u32, u32) {
    let (z, x, y) = tile_zxy(id);
    (z, x as u32, y as u32)
}

/// Six tiles at z4: ids change per seed, so base and segment hold different
/// bytes at the same tile exactly where asked.
fn six() -> Vec<u64> {
    (0..6u64).map(|n| tile_id(4, n, 0)).collect()
}

/// A segment hit wins over the base, and an unmentioned tile falls through.
#[test]
fn a_segment_hit_wins_and_a_miss_falls_through() {
    let ids = six();
    let base_bytes = archive(&ids.iter().map(|id| (*id, 1)).collect::<Vec<_>>());
    // Segment carries only tile 2 with new bytes (seed 2).
    let seg_bytes = archive(&[(ids[2], 2)]);
    let base = open(base_bytes);
    let seg = open(seg_bytes);
    let overlay =
        OverlayArchive::new(base, vec![seg], Vec::new(), Arc::new(LeafCache::new()));

    let (z, x, y) = zxy(ids[2]);
    let hit = overlay.tile(z, x, y).expect("read").expect("segment hit");
    assert_eq!(hit.layers[0].coords, vec![(0, 0), (2, 2)], "the segment's bytes win");

    let (z, x, y) = zxy(ids[0]);
    let miss = overlay.tile(z, x, y).expect("read").expect("base fallthrough");
    assert_eq!(miss.layers[0].coords, vec![(0, 0), (1, 1)], "a miss reads the base");
}

/// A tombstoned tile answers absent even though the base holds its old body.
#[test]
fn a_tombstone_answers_absent() {
    let ids = six();
    let base_bytes = archive(&ids.iter().map(|id| (*id, 1)).collect::<Vec<_>>());
    let base = open(base_bytes);
    let overlay =
        OverlayArchive::new(base, Vec::new(), vec![ids[3]], Arc::new(LeafCache::new()));

    let (z, x, y) = zxy(ids[3]);
    assert!(overlay.tile(z, x, y).expect("read").is_none(), "tombstoned is absent");
    let (z, x, y) = zxy(ids[4]);
    assert!(overlay.tile(z, x, y).expect("read").is_some(), "untombstoned still reads");
}

/// The batch path resolves per-slot: hit, tombstone, fallthrough, error-free.
#[test]
fn a_batch_resolves_each_slot_patch_first() {
    let ids = six();
    let base_bytes = archive(&ids.iter().map(|id| (*id, 1)).collect::<Vec<_>>());
    let seg_bytes = archive(&[(ids[1], 7)]);
    let base = open(base_bytes);
    let seg = open(seg_bytes);
    let overlay =
        OverlayArchive::new(base, vec![seg], vec![ids[5]], Arc::new(LeafCache::new()));

    let coords: Vec<(u8, u32, u32)> = ids.iter().map(|id| zxy(*id)).collect();
    let got = overlay.tiles(&coords);
    assert_eq!(got.len(), 6);
    // Slot 1: segment bytes. Slot 5: tombstone absent. Rest: base bytes.
    let b1 = got[1].as_ref().expect("slot 1 ok").as_ref().expect("slot 1 hit");
    assert_eq!(b1.layers[0].coords, vec![(0, 0), (7, 7)]);
    assert!(got[5].as_ref().expect("slot 5 ok").is_none(), "slot 5 tombstoned");
    for n in [0, 2, 3, 4] {
        let b = got[n].as_ref().expect("slot ok").as_ref().expect("base hit");
        assert_eq!(b.layers[0].coords, vec![(0, 0), (1, 1)], "slot {n} reads the base");
    }
}

/// Newest segment wins when two carry the same tile.
#[test]
fn the_newest_segment_wins() {
    let ids = six();
    let base_bytes = archive(&ids.iter().map(|id| (*id, 1)).collect::<Vec<_>>());
    let older_bytes = archive(&[(ids[0], 10)]);
    let newer_bytes = archive(&[(ids[0], 20)]);
    let base = open(base_bytes);
    let older = open(older_bytes);
    let newer = open(newer_bytes);
    let overlay = OverlayArchive::new(
        base,
        vec![newer, older],
        Vec::new(),
        Arc::new(LeafCache::new()),
    );

    let (z, x, y) = zxy(ids[0]);
    let hit = overlay.tile(z, x, y).expect("read").expect("hit");
    assert_eq!(hit.layers[0].coords, vec![(0, 0), (20, 20)], "newest wins");
}

/// The overlay reports the base header (range, build id), never a segment's.
#[test]
fn the_header_is_the_base_header() {
    let ids = six();
    let base_bytes = archive(&ids.iter().map(|id| (*id, 1)).collect::<Vec<_>>());
    let seg_bytes = archive(&[(ids[0], 9)]);
    let base_build = open(base_bytes).header.build_id;
    let base = open(archive(&ids.iter().map(|id| (*id, 1)).collect::<Vec<_>>()));
    let seg = open(seg_bytes);
    let overlay =
        OverlayArchive::new(base, vec![seg], Vec::new(), Arc::new(LeafCache::new()));
    assert_eq!(overlay.header().build_id, base_build, "base identity wins");
    assert!(tilecodec::mamaps::read::base_matches(&overlay, base_build));
    assert!(!tilecodec::mamaps::read::base_matches(&overlay, base_build.wrapping_add(1)));
}
