//! `overlay.bin`: the motorway/trunk/primary subgraph of a finished v7 graph,
//! renumbered densely so a long on-device search reads contiguous blocks.
//!
//! Read side and layout: `maps/src/main/rust/src/graph_overlay.rs`; the two must
//! agree byte for byte.
//!
//! ```text
//! [magic "MOVL" u32][version u32]
//! [full_node_count u64][full_edge_count u64]
//! [overlay_node_count u64 M][overlay_edge_count u64 K]
//! [nodes_len u64][edges_len u64]
//! nodes: ZBLK, 16 B (lat_e7 i32, lon_e7 i32, edge_ptr u32, full_node u32), M + 1
//!        records, the sentinel's edge_ptr = K
//! pad to 8
//! edges: ZBLK, 20 B (target u32 overlay id, dist_mm u32, full_edge u64,
//!        type u8, speed_limit u8, pad u16), K records, grouped by source
//! pad to 8
//! portals: rank u64[N.div_ceil(512) + 1] then present u8[N.div_ceil(8)] over
//!          the full node ids; overlay id = rank(full id)
//! ```
//!
//! Overlay ids are assigned in increasing full-id order, so they inherit the
//! graph's spatial ordering and the rank is the whole id map.

use std::io::{self, Write as _};
use std::path::Path;

use tilecodec::blocked::{BlockCache, Blocked, BlockedStreamWriter};

pub const OVERLAY_MAGIC: u32 = 0x4C56_4F4D;
pub const OVERLAY_VERSION: u32 = 1;
const NODE_LEN: usize = 16;
const EDGE_LEN: usize = 20;
const GRAPH_MAGIC: u32 = 0x4752_414D;
const ESCAPE_BLOCK: u64 = 1024;
const TARGET_DELTA_ESCAPE: i16 = i16::MIN;
const DIST_MM_ESCAPE: u32 = 0xFF_FFFF;
/// Road-class bits of an edge's type byte (bit 6 is the reverse-geometry flag).
const CLASS_MASK: u8 = 0x3F;
/// Highest road class on the overlay: motorway 1, trunk 2, primary 3 (their
/// `*_link` ramps share the parent's number, see [`crate::tags::get_hw_id`]).
const MAX_CLASS: u8 = 3;
const RANK_BLOCK_BYTES: u64 = 64;

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct OverlayStats {
    pub full_nodes: u64,
    pub full_edges: u64,
    pub nodes: u64,
    pub edges: u64,
    pub bytes: u64,
}

fn bad(msg: impl Into<String>) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, msg.into())
}

fn map(path: &Path) -> io::Result<memmap2::Mmap> {
    let f = std::fs::File::open(path)?;
    // SAFETY: read-only map of a finished build output nothing else writes.
    unsafe { memmap2::Mmap::map(&f) }
}

fn u32_at(b: &[u8], at: usize) -> u32 {
    u32::from_le_bytes(b[at..at + 4].try_into().expect("4 bytes"))
}

fn u64_at(b: &[u8], at: usize) -> u64 {
    u64::from_le_bytes(b[at..at + 8].try_into().expect("8 bytes"))
}

fn align8(n: u64) -> u64 {
    (n + 7) & !7
}

/// One full-graph edge as the overlay needs it.
#[derive(Clone, Copy)]
struct FullEdge {
    target: u32,
    dist_mm: u32,
    type_: u8,
    speed_limit: u8,
}

/// Sequential reader over a v7 graph: visits every node in id order with its
/// out-edges, inflating each block once.
struct GraphWalk<'a> {
    node_count: u32,
    nodes: Blocked<'a>,
    edges: Blocked<'a>,
    edges_file: &'a [u8],
    escape_rows_off: usize,
    escape_count: u64,
}

impl<'a> GraphWalk<'a> {
    fn open(meta: &[u8], nodes: &'a [u8], edges: &'a [u8]) -> io::Result<GraphWalk<'a>> {
        if meta.len() < 40 || u32_at(meta, 0) != GRAPH_MAGIC {
            return Err(bad("metadata.bin is not a MARG header"));
        }
        if u32_at(meta, 4) != 7 {
            return Err(bad(format!("graph version {} — the overlay reads v7 only", u32_at(meta, 4))));
        }
        let node_count = u64_at(meta, 8);
        let edge_count = u64_at(meta, 16);
        let escape_count = u64_at(meta, 24);
        let node_count = u32::try_from(node_count).map_err(|_| bad("node count past u32"))?;
        let nodes_b = Blocked::parse(nodes).map_err(|e| bad(format!("nodes.bin: {e}")))?;
        let edges_b = Blocked::parse(edges).map_err(|e| bad(format!("edges.bin: {e}")))?;
        if nodes_b.record_len() != 12 || nodes_b.unpacked_len() != (u64::from(node_count) + 1) * 12 {
            return Err(bad("nodes.bin does not match the header's node count"));
        }
        if edges_b.record_len() != 7 || edges_b.unpacked_len() != edge_count * 7 {
            return Err(bad("edges.bin does not match the header's edge count"));
        }
        let rec_end = edges_b.block_offset(edges_b.block_count()).ok_or_else(|| bad("edges.bin has no end offset"))?;
        let escape_first_off = align8(rec_end);
        let escape_blocks = edge_count.div_ceil(ESCAPE_BLOCK) + 1;
        let escape_rows_off = (escape_first_off + escape_blocks * 4) as usize;
        if escape_rows_off as u64 + escape_count * 12 > edges.len() as u64 {
            return Err(bad("edges.bin escape table runs past the file"));
        }
        Ok(GraphWalk {
            node_count,
            nodes: nodes_b,
            edges: edges_b,
            edges_file: edges,
            escape_rows_off,
            escape_count,
        })
    }

    /// Call `f(u, lat_e7, lon_e7, &[(edge_idx, FullEdge)])` for every node.
    fn for_each(&self, mut f: impl FnMut(u32, i32, i32, &[(u64, FullEdge)]) -> io::Result<()>) -> io::Result<()> {
        let (mut ncache, mut ecache) = (BlockCache::new(1), BlockCache::new(1));
        let per = 4096u64;
        let mut node_rec = |id: u64, cache: &mut BlockCache| -> io::Result<(i32, i32, u64)> {
            let b = cache.get(&self.nodes, (id / per) as usize).map_err(|e| bad(format!("nodes.bin: {e}")))?;
            let at = (id % per) as usize * 12;
            let r = &b[at..at + 12];
            Ok((
                i32::from_le_bytes(r[0..4].try_into().expect("4")),
                i32::from_le_bytes(r[4..8].try_into().expect("4")),
                u64::from(u32::from_le_bytes(r[8..12].try_into().expect("4"))),
            ))
        };
        let mut escape_row = 0u64;
        let mut out: Vec<(u64, FullEdge)> = Vec::new();
        let (mut lat, mut lon, mut lo) = node_rec(0, &mut ncache)?;
        for u in 0..self.node_count {
            let (nlat, nlon, hi) = node_rec(u64::from(u) + 1, &mut ncache)?;
            out.clear();
            for i in lo..hi {
                let b = ecache.get(&self.edges, (i / per) as usize).map_err(|e| bad(format!("edges.bin: {e}")))?;
                let at = (i % per) as usize * 7;
                let r = &b[at..at + 7];
                let delta = i16::from_le_bytes([r[0], r[1]]);
                let dist = u32::from_le_bytes([r[2], r[3], r[4], 0]);
                let (target, dist_mm) = if delta == TARGET_DELTA_ESCAPE || dist == DIST_MM_ESCAPE {
                    // Escape rows are sorted by edge index, and edges are visited
                    // in index order, so one cursor finds every row.
                    loop {
                        if escape_row >= self.escape_count {
                            return Err(bad(format!("edge {i} is escaped but has no escape row")));
                        }
                        let at = self.escape_rows_off + escape_row as usize * 12;
                        let row = &self.edges_file[at..at + 12];
                        escape_row += 1;
                        if u64::from(u32_at(row, 0)) == i {
                            break (u32_at(row, 4), u32_at(row, 8));
                        }
                    }
                } else {
                    (u.wrapping_add_signed(i32::from(delta)), dist)
                };
                out.push((i, FullEdge { target, dist_mm, type_: r[5], speed_limit: r[6] }));
            }
            f(u, lat, lon, &out)?;
            (lat, lon, lo) = (nlat, nlon, hi);
        }
        Ok(())
    }
}

fn on_overlay(type_: u8) -> bool {
    (1..=MAX_CLASS).contains(&(type_ & CLASS_MASK))
}

/// Build `overlay.bin` beside the graph in `graph_dir` (or at `out`).
pub fn build(graph_dir: &Path, out: &Path) -> io::Result<OverlayStats> {
    let meta = std::fs::read(graph_dir.join("metadata.bin"))?;
    let nodes = map(&graph_dir.join("nodes.bin"))?;
    let edges = map(&graph_dir.join("edges.bin"))?;
    build_from(&meta, &nodes, &edges, out)
}

/// [`build`] over section bytes, so an archive's graph sections can be used
/// without unpacking them to a directory.
pub fn build_from(meta: &[u8], nodes: &[u8], edges: &[u8], out: &Path) -> io::Result<OverlayStats> {
    let walk = GraphWalk::open(meta, nodes, edges)?;
    let n = walk.node_count as u64;
    let full_edges = u64_at(meta, 16);

    // Pass 1: every endpoint of an arterial edge is on the overlay.
    let mut present = vec![0u8; n.div_ceil(8) as usize];
    let mut set = |id: u32| present[(id / 8) as usize] |= 1 << (id % 8);
    walk.for_each(|u, _, _, es| {
        for (_, e) in es.iter().filter(|(_, e)| on_overlay(e.type_)) {
            if u64::from(e.target) >= n {
                return Err(bad(format!("edge from {u} targets {} past the node count", e.target)));
            }
            set(u);
            set(e.target);
        }
        Ok(())
    })?;

    // Rank index: set bits before each 64-byte block, then the total.
    let blocks = n.div_ceil(RANK_BLOCK_BYTES * 8);
    let mut rank = Vec::with_capacity(blocks as usize + 1);
    let mut running = 0u64;
    for b in 0..=blocks {
        rank.push(running);
        let lo = (b * RANK_BLOCK_BYTES) as usize;
        let hi = (lo + RANK_BLOCK_BYTES as usize).min(present.len());
        if lo < hi {
            running += present[lo..hi].iter().map(|x| u64::from(x.count_ones())).sum::<u64>();
        }
    }
    let m = running;
    let of = |id: u32| -> u32 {
        let byte = u64::from(id) / 8;
        let block = byte / RANK_BLOCK_BYTES;
        let mut r = rank[block as usize];
        for x in &present[(block * RANK_BLOCK_BYTES) as usize..byte as usize] {
            r += u64::from(x.count_ones());
        }
        (r + u64::from((present[byte as usize] & ((1u8 << (id % 8)) - 1)).count_ones())) as u32
    };
    let is_set = |id: u32| present[(id / 8) as usize] & (1 << (id % 8)) != 0;

    // Pass 2: emit nodes in full-id order with their arterial out-edges.
    let tmp = |s: &str| out.with_extension(s);
    let mut node_w = BlockedStreamWriter::create(NODE_LEN, &tmp("nodes.tmp"))?;
    let mut edge_w = BlockedStreamWriter::create(EDGE_LEN, &tmp("edges.tmp"))?;
    let mut k = 0u64;
    walk.for_each(|u, lat, lon, es| {
        if !is_set(u) {
            return Ok(());
        }
        let ptr = u32::try_from(k).map_err(|_| bad("overlay edge count past u32"))?;
        let mut rec = [0u8; NODE_LEN];
        rec[0..4].copy_from_slice(&lat.to_le_bytes());
        rec[4..8].copy_from_slice(&lon.to_le_bytes());
        rec[8..12].copy_from_slice(&ptr.to_le_bytes());
        rec[12..16].copy_from_slice(&u.to_le_bytes());
        node_w.push(&rec)?;
        for (i, e) in es.iter().filter(|(_, e)| on_overlay(e.type_)) {
            let mut rec = [0u8; EDGE_LEN];
            rec[0..4].copy_from_slice(&of(e.target).to_le_bytes());
            rec[4..8].copy_from_slice(&e.dist_mm.to_le_bytes());
            rec[8..16].copy_from_slice(&i.to_le_bytes());
            rec[16] = e.type_;
            rec[17] = e.speed_limit;
            edge_w.push(&rec)?;
            k += 1;
        }
        Ok(())
    })?;
    let mut sentinel = [0u8; NODE_LEN];
    sentinel[8..12].copy_from_slice(&u32::try_from(k).map_err(|_| bad("overlay edge count past u32"))?.to_le_bytes());
    sentinel[12..16].copy_from_slice(&u32::MAX.to_le_bytes());
    node_w.push(&sentinel)?;
    let nodes_sec = node_w.finish()?;
    let edges_sec = edge_w.finish()?;

    let mut f = io::BufWriter::new(std::fs::File::create(out)?);
    f.write_all(&OVERLAY_MAGIC.to_le_bytes())?;
    f.write_all(&OVERLAY_VERSION.to_le_bytes())?;
    for v in [n, full_edges, m, k, nodes_sec.len() as u64, edges_sec.len() as u64] {
        f.write_all(&v.to_le_bytes())?;
    }
    let nodes_end = 56 + nodes_sec.len() as u64;
    let edges_end = align8(nodes_end) + edges_sec.len() as u64;
    f.write_all(&nodes_sec)?;
    f.write_all(&vec![0u8; (align8(nodes_end) - nodes_end) as usize])?;
    f.write_all(&edges_sec)?;
    f.write_all(&vec![0u8; (align8(edges_end) - edges_end) as usize])?;
    for r in &rank {
        f.write_all(&r.to_le_bytes())?;
    }
    f.write_all(&present)?;
    f.flush()?;
    let bytes = align8(edges_end) + rank.len() as u64 * 8 + present.len() as u64;
    Ok(OverlayStats { full_nodes: n, full_edges, nodes: m, edges: k, bytes })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A motorway A-B-C, a motorway_link C-D, residential D-E and B-F.
    fn sample(tag: &str) -> std::path::PathBuf {
        let p = |i: i32| (370_000_000 + i * 10_000, -1_220_000_000 + i * 10_000);
        let nodes: Vec<(i64, i32, i32)> = (1..=6).map(|id| (id as i64, p(id).0, p(id).1)).collect();
        let nodes = [nodes[0], nodes[1], nodes[2], nodes[3], nodes[4], (6, p(2).0 + 5_000, p(2).1 - 5_000)];
        let ways: [(i64, &str, &[i64]); 4] = [
            (10, "motorway", &[1, 2, 3]),
            (11, "motorway_link", &[3, 4]),
            (12, "residential", &[4, 5]),
            (13, "residential", &[2, 6]),
        ];
        let (pbf, dir) = crate::testpbf::write_highway_sample(tag, &nodes, &ways);
        crate::graph_build::build(&pbf, &dir).unwrap();
        dir
    }

    struct Decoded {
        header: [u64; 6],
        nodes: Vec<(i32, i32, u32, u32)>,
        edges: Vec<(u32, u32, u64, u8)>,
    }

    fn decode(bytes: &[u8]) -> Decoded {
        assert_eq!(u32_at(bytes, 0), OVERLAY_MAGIC);
        assert_eq!(u32_at(bytes, 4), OVERLAY_VERSION);
        let header: [u64; 6] = std::array::from_fn(|i| u64_at(bytes, 8 + i * 8));
        let (nodes_len, edges_len) = (header[4] as usize, header[5] as usize);
        let inflate = |b: &[u8]| {
            let v = Blocked::parse(b).unwrap();
            let mut c = BlockCache::new(0);
            (0..v.block_count()).flat_map(|i| c.get(&v, i).unwrap().clone()).collect::<Vec<u8>>()
        };
        let nb = inflate(&bytes[56..56 + nodes_len]);
        let edges_at = align8(56 + nodes_len as u64) as usize;
        let eb = inflate(&bytes[edges_at..edges_at + edges_len]);
        let nodes = nb
            .chunks(NODE_LEN)
            .map(|r| (i32::from_le_bytes(r[0..4].try_into().unwrap()), i32::from_le_bytes(r[4..8].try_into().unwrap()), u32_at(r, 8), u32_at(r, 12)))
            .collect();
        let edges = eb.chunks(EDGE_LEN).map(|r| (u32_at(r, 0), u32_at(r, 4), u64_at(r, 8), r[16])).collect();
        Decoded { header, nodes, edges }
    }

    #[test]
    fn the_overlay_holds_the_arterials_and_their_ramps_only() {
        let dir = sample("overlay_arterials");
        let out = dir.join("overlay.bin");
        let stats = build(&dir, &out).unwrap();
        let d = decode(&std::fs::read(&out).unwrap());
        assert_eq!(d.header[0], stats.full_nodes);
        assert_eq!((d.header[2], d.header[3]), (stats.nodes, stats.edges));
        assert_eq!(d.nodes.len() as u64, stats.nodes + 1, "plus the sentinel");
        assert_eq!(d.nodes.last().unwrap().2 as u64, stats.edges, "sentinel edge_ptr is K");
        // Coordinates identify the full nodes regardless of compaction.
        let p = |i: i32| (370_000_000 + i * 10_000, -1_220_000_000 + i * 10_000);
        let coords: Vec<(i32, i32)> = d.nodes[..stats.nodes as usize].iter().map(|n| (n.0, n.1)).collect();
        for id in [1, 4] {
            assert!(coords.contains(&p(id)), "motorway end / ramp end {id} is on the overlay");
        }
        assert!(!coords.contains(&p(5)), "the residential-only end is not");
        // Each overlay edge is the full edge it names: same source, same target.
        let meta = std::fs::read(dir.join("metadata.bin")).unwrap();
        let (nodes, edges) = (map(&dir.join("nodes.bin")).unwrap(), map(&dir.join("edges.bin")).unwrap());
        let walk = GraphWalk::open(&meta, &nodes, &edges).unwrap();
        let mut full: Vec<(u32, u64, u32, u8)> = Vec::new();
        walk.for_each(|u, _, _, es| {
            full.extend(es.iter().map(|(i, e)| (u, *i, e.target, e.type_)));
            Ok(())
        })
        .unwrap();
        let mut k = 0usize;
        for (o, n) in d.nodes[..stats.nodes as usize].iter().enumerate() {
            let end = d.nodes[o + 1].2 as usize;
            for &(t, _, full_edge, ty) in &d.edges[n.2 as usize..end] {
                let &(src, _, tgt, fty) = full.iter().find(|f| f.1 == full_edge).unwrap();
                assert_eq!(src, n.3, "overlay node {o} owns full edge {full_edge}");
                assert_eq!(tgt, d.nodes[t as usize].3, "target maps back");
                assert_eq!(ty, fty);
                assert!(on_overlay(ty));
                k += 1;
            }
        }
        assert_eq!(k as u64, stats.edges);
        assert_eq!(k, full.iter().filter(|f| on_overlay(f.3)).count(), "every arterial edge, nothing else");
        // Full ids ascend, so the rank map is exact.
        assert!(d.nodes[..stats.nodes as usize].windows(2).all(|w| w[0].3 < w[1].3));
    }

    #[test]
    fn the_overlay_is_byte_identical_across_runs() {
        let dir = sample("overlay_determinism");
        build(&dir, &dir.join("a.bin")).unwrap();
        build(&dir, &dir.join("b.bin")).unwrap();
        assert_eq!(std::fs::read(dir.join("a.bin")).unwrap(), std::fs::read(dir.join("b.bin")).unwrap());
    }
}
