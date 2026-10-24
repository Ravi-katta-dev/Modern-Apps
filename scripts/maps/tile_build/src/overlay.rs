//! `overlay` — build patch-overlay segments from two `.mamaps` archives.
//!
//! Phase 1 server generator: input is the old (base) and new packed
//! archives, output is overlay segments only, never a rewritten base. Each
//! segment is a small self-contained `.mamaps` file written by the existing
//! [`StreamWriter`](crate::mamaps::write::StreamWriter) — same header,
//! dictionary, root, leaves and body format as the base — holding only
//! changed/added tile bodies with overlay-relative offsets. Deleted tiles
//! are tombstone tile ids in the overlay descriptor, not bodies (a
//! tombstone has no bytes to store).
//!
//! Insertion efficiency falls out of the layout: the base is never touched,
//! so a mid-world insert appends one body to an overlay segment plus one
//! overlay leaf entry. No base `offset_delta`, `base_data_offset`, sidecar
//! offset or footer movement.
//!
//! Bodies travel through [`MamapsArchive::tile_bytes`] (decompressed raw)
//! and back in through [`StreamWriter::append_encoded`] (re-compressed).
//! DEFLATE is deterministic, so a body the new build wrote compresses to
//! the same stored bytes here — the segment carries what the new archive
//! carries, byte for byte.
use crate::churn::{FileReader, Snapshot, snapshot};

use tilecodec::stream::RangeReader;

use crate::mamaps::archive::{ArchiveView, unified_build_id};
use crate::mamaps::read::MamapsArchive;
use crate::mamaps::write::Options;
use crate::manifest::SEGMENT_CAP_BYTES;
use crate::pmtiles::tile_zxy;
use crate::proto::{Result, err};

/// One tile the overlay must carry: its id and its new stored length (for
/// segment packing); the bytes are fetched at emit time.
#[derive(Debug, Clone, Copy)]
pub struct Carry {
    /// Tile id in the new archive.
    pub tile_id: u64,
    /// Stored body length in the new archive (compressed, if it says so).
    pub length: u32,
}

/// The overlay plan: which new bodies ship, which old tiles are tombstoned,
/// and how the bodies split into cap-sized segments.
pub struct OverlayPlan {
    /// New bodies to ship, ascending by tile id.
    pub carries: Vec<Carry>,
    /// Deleted tile ids (old-only), ascending. No bodies ship for these.
    pub tombstones: Vec<u64>,
    /// Segment boundaries as `carries` index ranges (each segment's stored
    /// bodies fit [`SEGMENT_CAP_BYTES`]).
    pub segments: Vec<(usize, usize)>,
    /// `overlay_build_id` for every segment header + the manifest.
    pub overlay_build_id: u64,
    /// Zoom range + bbox + flags carried from the new archive's header.
    pub header: SegmentHeader,
}

/// Header facts the segment writer needs from the new archive.
#[derive(Debug, Clone, Copy)]
pub struct SegmentHeader {
    /// New archive's `build_id` (pinned in the manifest as the overlay source).
    pub new_build_id: u64,
    /// Base archive's `build_id` (compat pin).
    pub base_build_id: u64,
    /// New archive's zoom range (every carried tile is inside it).
    pub min_zoom: u8,
    /// New archive's zoom range.
    pub max_zoom: u8,
    /// New archive's bbox, carried so segments describe the same world.
    pub bbox: (i32, i32, i32, i32),
    /// Whether the new archive's rings are validated.
    pub rings_validated: bool,
}

/// Diff two snapshots into an [`OverlayPlan`].
///
/// Interval merge over tile-id covers (both ascending): same tile + same
/// fingerprint skips wholesale (a run re-split with identical bytes ships
/// nothing — the overlay carries bodies, not index shapes). Same tile +
/// different fingerprint, or new-only cover = carry. Old-only cover =
/// tombstone. No per-tile expansion: planet's ~63M addressed tiles never
/// materialise as a map.
///
/// Carries split greedily at [`SEGMENT_CAP_BYTES`] of stored bodies; the
/// index overhead per segment is kilobytes against a 512 MiB cap. Carries
/// may repeat a body (dedup-shared across tiles); the segment writer
/// re-collapses those.
pub fn plan(
    old: &Snapshot,
    new: &Snapshot,
    old_archive: &MamapsArchive<impl RangeReader>,
    new_archive: &MamapsArchive<impl RangeReader>,
) -> OverlayPlan {
    let mut carries: Vec<Carry> = Vec::new();
    let mut tombstones: Vec<(u64, u64)> = Vec::new();
    let (mut i, mut j) = (0usize, 0usize);
    while i < old.entries.len() && j < new.entries.len() {
        let a = &old.entries[i];
        let b = &new.entries[j];
        let (a0, a1) = (a.tile_id, a.tile_id + u64::from(a.run_length));
        let (b0, b1) = (b.tile_id, b.tile_id + u64::from(b.run_length));
        if a1 <= b0 {
            tombstones.push((a0, a1));
            i += 1;
        } else if b1 <= a0 {
            push_carry_range(&mut carries, b, b0, b1);
            j += 1;
        } else if a.hash == b.hash {
            // Overlap with identical bytes: skip the overlap, advance the
            // cover that ends first.
            if a1 <= b1 {
                i += 1;
            }
            if b1 <= a1 {
                j += 1;
            }
        } else {
            // Overlap with different bytes: the overlap carries the new body.
            let (lo, hi) = (a0.max(b0), a1.min(b1));
            push_carry_span(&mut carries, b, lo, hi);
            if a1 <= b1 {
                i += 1;
            }
            if b1 <= a1 {
                j += 1;
            }
        }
    }
    for a in &old.entries[i..] {
        tombstones.push((a.tile_id, a.tile_id + u64::from(a.run_length)));
    }
    for b in &new.entries[j..] {
        push_carry_range(&mut carries, b, b.tile_id, b.tile_id + u64::from(b.run_length));
    }
    // Flatten tombstone ranges to ids (tombstones are rare: real deletes, not
    // coverage edges — a coverage-edge tombstone storm means the wrong pair).
    let mut tombstone_ids = Vec::new();
    for (lo, hi) in tombstones {
        tombstone_ids.extend(lo..hi);
    }
    let mut segments = Vec::new();
    let mut start = 0usize;
    let mut bytes = 0u64;
    for (n, c) in carries.iter().enumerate() {
        if n > start && bytes + u64::from(c.length) > SEGMENT_CAP_BYTES {
            segments.push((start, n));
            start = n;
            bytes = 0;
        }
        bytes += u64::from(c.length);
    }
    if start < carries.len() || carries.is_empty() {
        segments.push((start, carries.len()));
    }
    let new_hdr = &new_archive.header;
    let overlay_build_id = unified_build_id(
        b"overlay-v1",
        &old_archive.header.build_id.to_le_bytes(),
        &new_hdr.build_id.to_le_bytes(),
        &[],
        b"mamaps-overlay-v1",
    );
    OverlayPlan {
        carries,
        tombstones: tombstone_ids,
        segments,
        overlay_build_id,
        header: SegmentHeader {
            new_build_id: new_hdr.build_id,
            base_build_id: old_archive.header.build_id,
            min_zoom: new_hdr.min_zoom,
            max_zoom: new_hdr.max_zoom,
            bbox: (new_hdr.min_lon_e7, new_hdr.min_lat_e7, new_hdr.max_lon_e7, new_hdr.max_lat_e7),
            rings_validated: new_hdr.rings_validated(),
        },
    }
}

/// Append one carry per tile of `[lo, hi)` (all inside one new entry, so one body).
fn push_carry_span(carries: &mut Vec<Carry>, entry: &crate::churn::Entry, lo: u64, hi: u64) {
    // One carry per tile: the body repeats per tile here (dedup-shared
    // across tiles) and the segment writer re-collapses identical bodies.
    for t in lo..hi {
        carries.push(Carry { tile_id: t, length: entry.length });
    }
}

/// Append carries for a whole new cover `[lo, hi)` known to be new-only.
fn push_carry_range(carries: &mut Vec<Carry>, entry: &crate::churn::Entry, lo: u64, hi: u64) {
    push_carry_span(carries, entry, lo, hi);
}

/// Emit one segment file: fetch each carried tile's raw body from the new
/// archive and append it (ascending — carries are ascending by construction).
pub fn emit_segment<R: RangeReader>(
    new_archive: &MamapsArchive<R>,
    plan: &OverlayPlan,
    range: (usize, usize),
    out: &std::path::Path,
) -> Result<u64> {
    let options = Options {
        min_zoom: plan.header.min_zoom,
        max_zoom: plan.header.max_zoom,
        build_id: plan.overlay_build_id,
        compress: true,
        rings_validated: plan.header.rings_validated,
        min_lon_e7: plan.header.bbox.0,
        min_lat_e7: plan.header.bbox.1,
        max_lon_e7: plan.header.bbox.2,
        max_lat_e7: plan.header.bbox.3,
        ..Options::default()
    };
    let mut writer = crate::mamaps::write::StreamWriter::new(options)?;
    for carry in &plan.carries[range.0..range.1] {
        let (z, x, y) = tile_zxy(carry.tile_id);
        let Some(raw) = new_archive.tile_bytes(z, x as u32, y as u32)? else {
            return err(format!(
                "overlay carry tile {} vanished from the new archive",
                carry.tile_id,
            ));
        };
        writer.append_encoded(carry.tile_id, &raw)?;
    }
    writer.finish_to_path(out)?;
    Ok(std::fs::metadata(out)
        .map_err(|e| crate::proto::Error(format!("cannot stat {}: {e}", out.display())))?
        .len())
}

/// A sidecar section payload sliced out of the new archive, for chunked
/// section publishing (large kinds ship as files; the client skips
/// unchanged chunks by hash).
pub struct SectionPayload {
    /// Archive kind id.
    pub kind: u8,
    /// Verbatim payload bytes from the new archive.
    pub bytes: Vec<u8>,
}

/// Slice every sidecar section out of the new archive (tiles-only archives
/// yield none). Payloads are verbatim — no re-encode, no re-compress.
pub fn section_payloads(
    new_bytes_prefix_info: &NewArchiveSections,
) -> Vec<SectionPayload> {
    new_bytes_prefix_info.payloads.iter().map(|(kind, bytes)| SectionPayload {
        kind: *kind,
        bytes: bytes.clone(),
    }).collect()
}

/// The new archive's sidecar sections, read whole (they stream at publish
/// time; the generator box holds them transiently, like `mamaps_pack`).
pub struct NewArchiveSections {
    /// `(kind, payload bytes)` in kind order.
    pub payloads: Vec<(u8, Vec<u8>)>,
}

/// Read a packed archive's sidecar payloads whole, validated by
/// [`ArchiveView::parse`] (same checks the device loaders run).
pub fn read_new_sections(path: &std::path::Path) -> Result<NewArchiveSections> {
    let bytes = std::fs::read(path)
        .map_err(|e| crate::proto::Error(format!("cannot read {}: {e}", path.display())))?;
    let (header, _, _) = crate::mamaps::read::open_prefix(&bytes)?;
    let view = ArchiveView::parse(&bytes, &header)?;
    let mut payloads = Vec::with_capacity(view.entries.len());
    for e in &view.entries {
        payloads.push((e.kind, bytes[e.offset as usize..(e.offset + e.len) as usize].to_vec()));
    }
    Ok(NewArchiveSections { payloads })
}

/// Open both archives' tile indexes for planning + emitting.
pub struct ArchivePair {
    /// Base (old) archive over its file.
    pub old_archive: MamapsArchive<FileReader>,
    /// New archive over its file.
    pub new_archive: MamapsArchive<FileReader>,
    /// Base fingerprint snapshot.
    pub old_snapshot: Snapshot,
    /// New fingerprint snapshot.
    pub new_snapshot: Snapshot,
    /// Base file length (footer read).
    pub old_len: u64,
    /// New file length.
    pub new_len: u64,
}

/// Snapshot + open both archives. Range-based (no whole-file reads) except
/// through the fingerprint pass, which streams distinct bodies once.
pub fn open_pair(before: &std::path::Path, after: &std::path::Path) -> Result<ArchivePair> {
    let old_len = std::fs::metadata(before)
        .map_err(|e| crate::proto::Error(format!("cannot stat {}: {e}", before.display())))?
        .len();
    let new_len = std::fs::metadata(after)
        .map_err(|e| crate::proto::Error(format!("cannot stat {}: {e}", after.display())))?
        .len();
    let old_reader = FileReader::open(before)?;
    let new_reader = FileReader::open(after)?;
    let old_snapshot = snapshot(&old_reader, old_len)?;
    let new_snapshot = snapshot(&new_reader, new_len)?;
    let old_archive = MamapsArchive::open(FileReader::open(before)?)?;
    let new_archive = MamapsArchive::open(FileReader::open(after)?)?;
    Ok(ArchivePair { old_archive, new_archive, old_snapshot, new_snapshot, old_len, new_len })
}
