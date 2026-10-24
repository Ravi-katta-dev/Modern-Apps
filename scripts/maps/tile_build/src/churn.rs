//! `churn` — byte-level leaf churn between two `.mamaps` archives.
//!
//! Phase 0 sizing gate for incremental updates: how many stored tile bodies
//! would a patch-overlay have to carry from build A to build B, and how many
//! sidecar bytes moved per section kind. Answers the overlay budget question
//! (low-single-digit GB?) without decoding a single tile.
//!
//! What is compared is the STORED bytes, not the picture: the overlay ships
//! stored bodies, so a byte difference ships even when the geometry did not
//! move (e.g. `subdivide` ring rotation). `test/diff_mamaps.py` stays the
//! picture check; this is the byte check.
//!
//! Cost model: two passes per archive, both range-based, no whole-file reads.
//! Pass 1 walks the leaf index only (root from the open prefix, one read per
//! leaf — the same walk as `mamaps_probe --breakdown`). Pass 2 fetches each
//! DISTINCT stored body once (dedup-shared bodies hash once) and FNV-1a-hashes
//! it. Bodies are never inflated or parsed. Peak memory is one compact entry
//! per stored body (~24 B: tile id, run, length, hash) on each side, plus a
//! transient offset table dropped after fingerprinting.
//!
//! Body identity is the fingerprint, not the offset: offsets always shift
//! between builds (packed layout), so comparing offsets would report
//! everything. Two tiles with equal fingerprints hold equal bodies even when
//! run boundaries re-split around them, and report unchanged. The join is an
//! interval merge over tile-id ranges (not entry-by-entry), so a re-split run
//! covering the same tiles with the same bytes counts unchanged.

use std::collections::{BTreeMap, HashMap, HashSet};

use tilecodec::stream::RangeReader;

use crate::mamaps::archive::{
    ARCHIVE_DIR_HEADER_LEN, ARCHIVE_ENTRY_LEN, ARCHIVE_FOOTER_LEN, ARCHIVE_MAGIC, ArchiveEntry,
    ArchiveFooter,
};
use crate::mamaps::{header, index};
use crate::mamaps::write::hash64;
use crate::pmtiles::tile_zxy;
use crate::proto::{Result, err};

/// One stored body's identity: where its tiles are, how big the body is, and
/// what bytes it holds. Offsets are archive-local and never compared.
#[derive(Debug, Clone, Copy)]
pub struct Entry {
    /// First tile id this entry covers.
    pub tile_id: u64,
    /// Consecutive tiles sharing this body. Never zero.
    pub run_length: u32,
    /// Stored body length (compressed, if the header says bodies are).
    pub length: u32,
    /// FNV-1a over the stored body bytes.
    pub hash: u64,
}

/// One archive's fingerprint set: its entries plus the sidecar directory.
pub struct Snapshot {
    /// One per stored body, ascending by tile id (leaf-walk order).
    pub entries: Vec<Entry>,
    /// Sidecar `(kind, length)` from the `MAMA8` directory. Empty for a
    /// tiles-only archive (no footer).
    pub sidecars: Vec<(u8, u64)>,
}

/// A file-backed [`RangeReader`], like `mamaps_probe`'s: positional reads
/// with short-on-past-end semantics, so tools get random access without
/// mapping multi-GB files.
pub struct FileReader {
    file: std::sync::Mutex<std::fs::File>,
}

impl FileReader {
    /// Open `path` for range reads.
    pub fn open(path: &std::path::Path) -> Result<FileReader> {
        let file = std::fs::File::open(path)
            .map_err(|e| crate::proto::Error(format!("cannot open {}: {e}", path.display())))?;
        Ok(FileReader { file: std::sync::Mutex::new(file) })
    }
}

impl RangeReader for FileReader {
    fn read(&self, offset: u64, length: u32) -> Result<Vec<u8>> {
        use std::io::{Read as _, Seek as _};
        let mut file = self
            .file
            .lock()
            .map_err(|_| crate::proto::Error("churn reader lock failed".to_string()))?;
        file.seek(std::io::SeekFrom::Start(offset))
            .map_err(|e| crate::proto::Error(format!("seek {offset}: {e}")))?;
        let mut buf = vec![0u8; length as usize];
        let mut got = 0usize;
        while got < buf.len() {
            match file.read(&mut buf[got..]) {
                Ok(0) => break,
                Ok(n) => got += n,
                Err(e) => return err(format!("read at {offset}: {e}")),
            }
        }
        buf.truncate(got);
        Ok(buf)
    }
}

/// Walk one archive's leaf index (no bodies) and fingerprint each distinct body.
///
/// `file_len` is the archive's length in bytes (for the footer read); it is a
/// parameter because [`RangeReader`] has no length.
pub fn snapshot<R: RangeReader>(reader: &R, file_len: u64) -> Result<Snapshot> {
    let prefix = reader.read(0, tilecodec::stream::OPEN_PREFIX_BYTES)?;
    let hdr = header::Header::parse(&prefix)?;
    let root_bytes = prefix_section(&prefix, hdr.root_offset, hdr.root_len as u64, "root index")?;
    let root = index::parse_root(root_bytes)?;
    // Located entries first (offsets needed for fingerprinting), compacted after.
    let mut located: Vec<(u64, u32, u32, u64)> = Vec::new();
    for root_entry in &root {
        let raw_len = root_entry.leaf_entry_count as u64 * index::LEAF_ENTRY_LEN as u64;
        let stored_len =
            if root_entry.stored_len == 0 { raw_len } else { root_entry.stored_len as u64 };
        let stored = reader.read(hdr.leaf_offset + root_entry.leaf_offset, stored_len as u32)?;
        if stored.len() as u64 != stored_len {
            return err("a .mamaps leaf read came back short".to_string());
        }
        let raw = if root_entry.stored_len == 0 {
            stored
        } else {
            index::decompress_leaf(&stored, raw_len as usize)?
        };
        for e in index::parse_leaf(&raw)? {
            let offset = hdr.data_offset + root_entry.base_data_offset + e.offset_delta as u64;
            located.push((
                root_entry.base_tile_id + e.tile_id_lo as u64,
                e.run_length,
                e.length,
                offset,
            ));
        }
    }
    let entries = fingerprint(reader, &located)?;
    let sidecars = read_sidecar_dir(reader, file_len)?;
    Ok(Snapshot { entries, sidecars })
}

/// Hash every distinct stored body once; entries sharing a body share its hash.
///
/// `located` is `(tile_id, run_length, length, offset)` in entry order. The
/// returned entries drop the offsets: they are archive-local and useless for
/// the join, and dropping them keeps the snapshot at ~24 B per body.
fn fingerprint<R: RangeReader>(
    reader: &R,
    located: &[(u64, u32, u32, u64)],
) -> Result<Vec<Entry>> {
    let mut hashes: HashMap<(u64, u32), u64> = HashMap::new();
    let mut entries = Vec::with_capacity(located.len());
    for (tile_id, run_length, length, offset) in located {
        let key = (*offset, *length);
        let hash = match hashes.get(&key) {
            Some(h) => *h,
            None => {
                let stored = reader.read(*offset, *length)?;
                if stored.len() as u64 != u64::from(*length) {
                    return err(format!(
                        "body at {offset} came back short: {} of {length} bytes",
                        stored.len(),
                    ));
                }
                let hash = hash64(&stored);
                hashes.insert(key, hash);
                hash
            }
        };
        entries.push(Entry { tile_id: *tile_id, run_length: *run_length, length: *length, hash });
    }
    Ok(entries)
}

/// The `MAMA8` section directory as `(kind, length)` pairs, or empty when the
/// file carries no footer (tiles-only archive).
fn read_sidecar_dir<R: RangeReader>(reader: &R, file_len: u64) -> Result<Vec<(u8, u64)>> {
    if file_len < ARCHIVE_FOOTER_LEN as u64 {
        return Ok(Vec::new());
    }
    let foot = reader.read(file_len - ARCHIVE_FOOTER_LEN as u64, ARCHIVE_FOOTER_LEN as u32)?;
    if foot.len() != ARCHIVE_FOOTER_LEN || foot[0..8] != ARCHIVE_MAGIC[..] {
        return Ok(Vec::new());
    }
    let footer = ArchiveFooter::parse(&foot)?;
    let dir = reader.read(footer.dir_offset, footer.dir_len as u32)?;
    if dir.len() as u64 != footer.dir_len {
        return err("a .mamaps section directory read came back short".to_string());
    }
    let count = u32::from_le_bytes([dir[0], dir[1], dir[2], dir[3]]) as usize;
    if ARCHIVE_DIR_HEADER_LEN + count * ARCHIVE_ENTRY_LEN > dir.len() {
        return err("a .mamaps section directory names more entries than it holds".to_string());
    }
    let mut out = Vec::with_capacity(count);
    for i in 0..count {
        let at = ARCHIVE_DIR_HEADER_LEN + i * ARCHIVE_ENTRY_LEN;
        let entry = ArchiveEntry::parse(&dir[at..at + ARCHIVE_ENTRY_LEN])?;
        out.push((entry.kind, entry.len));
    }
    Ok(out)
}

/// Slice `[offset, offset + len)` out of the open prefix, bounds-checked.
fn prefix_section<'a>(prefix: &'a [u8], offset: u64, len: u64, what: &str) -> Result<&'a [u8]> {
    let end =
        offset.checked_add(len).ok_or_else(|| crate::proto::Error(format!("{what} overflows")))?;
    if end > prefix.len() as u64 {
        return err(format!(
            "{what} ends at byte {end}, outside the {} byte prefix",
            tilecodec::stream::OPEN_PREFIX_BYTES,
        ));
    }
    Ok(&prefix[offset as usize..end as usize])
}

/// Per-zoom churn: addressed-tile counts plus the overlay's stored bytes there.
#[derive(Debug, Default, Clone, Copy)]
pub struct ZoomChurn {
    /// Tiles whose body bytes differ (same tile, new bytes).
    pub changed: u64,
    /// Stored bytes the changed tiles' new bodies occupy (distinct bodies).
    pub changed_bytes: u64,
    /// Tiles only the new archive holds.
    pub added: u64,
    /// Stored bytes the added tiles' new bodies occupy (distinct bodies).
    pub added_bytes: u64,
    /// Tiles only the old archive holds (cost overlay leaf entries, not bodies).
    pub deleted: u64,
}

/// The overlay budget answer: what an A-to-B patch-overlay would carry.
pub struct ChurnReport {
    /// New-archive stored bytes the overlay must ship (distinct bodies only).
    pub overlay_bytes: u64,
    /// Addressed tiles with different body bytes.
    pub changed: u64,
    /// Addressed tiles only the new archive holds.
    pub added: u64,
    /// Addressed tiles only the old archive holds.
    pub deleted: u64,
    /// Addressed tiles with identical body bytes (runs may re-split).
    pub unchanged: u64,
    /// Per-zoom breakdown, present zooms only.
    pub zooms: BTreeMap<u8, ZoomChurn>,
    /// Per-kind sidecar lengths `(kind, old_len or 0, new_len or 0)`, kind order.
    pub sidecars: Vec<(u8, u64, u64)>,
}

/// Interval-merge two snapshots (both ascending by tile id) into a [`ChurnReport`].
///
/// Each entry covers `[tile_id, tile_id + run_length)`; the merge walks the
/// overlap of the two covers. Same tile + same fingerprint = unchanged, even
/// across run re-splits: the overlay ships bodies, not index shapes. Same
/// tile + different fingerprint = changed. Cover on one side only =
/// added/deleted. Overlay bytes count each distinct new body once
/// (dedup-shared bodies ship once), attributed to the zoom of the first
/// changed/added tile carrying it.
///
/// A cover spanning a zoom boundary attributes to the zoom of its first tile;
/// ids are zoom-major so this only bites runs straddling a zoom edge.
pub fn join(old: &Snapshot, new: &Snapshot) -> ChurnReport {
    let mut report = ChurnReport {
        overlay_bytes: 0,
        changed: 0,
        added: 0,
        deleted: 0,
        unchanged: 0,
        zooms: BTreeMap::new(),
        sidecars: join_sidecars(&old.sidecars, &new.sidecars),
    };
    // Distinct new bodies already counted toward the overlay, by fingerprint.
    let mut shipped: HashSet<u64> = HashSet::new();
    let (mut i, mut j) = (0usize, 0usize);
    while i < old.entries.len() && j < new.entries.len() {
        let a = &old.entries[i];
        let b = &new.entries[j];
        let (a0, a1) = (a.tile_id, a.tile_id + u64::from(a.run_length));
        let (b0, b1) = (b.tile_id, b.tile_id + u64::from(b.run_length));
        if a1 <= b0 {
            // Old cover entirely before the new one: deleted.
            let span = a1 - a0;
            report.deleted += span;
            report.zooms.entry(tile_zxy(a0).0).or_default().deleted += span;
            i += 1;
        } else if b1 <= a0 {
            // New cover entirely before the old one: added.
            let span = b1 - b0;
            report.added += span;
            let zoom = report.zooms.entry(tile_zxy(b0).0).or_default();
            zoom.added += span;
            if shipped.insert(b.hash) {
                report.overlay_bytes += u64::from(b.length);
                zoom.added_bytes += u64::from(b.length);
            }
            j += 1;
        } else {
            // Overlap: fingerprint decides, then advance the cover that ends first.
            let (lo, hi) = (a0.max(b0), a1.min(b1));
            let span = hi - lo;
            let zoom = report.zooms.entry(tile_zxy(lo).0).or_default();
            if a.hash == b.hash {
                report.unchanged += span;
            } else {
                report.changed += span;
                zoom.changed += span;
                if shipped.insert(b.hash) {
                    report.overlay_bytes += u64::from(b.length);
                    zoom.changed_bytes += u64::from(b.length);
                }
            }
            if a1 <= b1 {
                i += 1;
            }
            if b1 <= a1 {
                j += 1;
            }
        }
    }
    for a in &old.entries[i..] {
        let span = u64::from(a.run_length);
        report.deleted += span;
        report.zooms.entry(tile_zxy(a.tile_id).0).or_default().deleted += span;
    }
    for b in &new.entries[j..] {
        let span = u64::from(b.run_length);
        report.added += span;
        let zoom = report.zooms.entry(tile_zxy(b.tile_id).0).or_default();
        zoom.added += span;
        if shipped.insert(b.hash) {
            report.overlay_bytes += u64::from(b.length);
            zoom.added_bytes += u64::from(b.length);
        }
    }
    report
}

/// Union of both sidecar kind sets with each side's length (0 when absent).
fn join_sidecars(old: &[(u8, u64)], new: &[(u8, u64)]) -> Vec<(u8, u64, u64)> {
    let (mut i, mut j) = (0usize, 0usize);
    let mut out = Vec::new();
    while i < old.len() && j < new.len() {
        let (ko, lo) = old[i];
        let (kn, ln) = new[j];
        if ko == kn {
            out.push((ko, lo, ln));
            i += 1;
            j += 1;
        } else if ko < kn {
            out.push((ko, lo, 0));
            i += 1;
        } else {
            out.push((kn, 0, ln));
            j += 1;
        }
    }
    for (k, l) in &old[i..] {
        out.push((*k, *l, 0));
    }
    for (k, l) in &new[j..] {
        out.push((*k, 0, *l));
    }
    out
}

/// Render the report as human-readable lines, ending in a machine-readable
/// `OVERLAY_BYTES=` line for `diff_mamaps.py --churn-budget-gb` to gate on.
pub fn render(report: &ChurnReport) -> String {
    let mut out = String::new();
    out.push_str(&format!(
        "tiles: unchanged={} changed={} added={} deleted={}\n",
        report.unchanged, report.changed, report.added, report.deleted,
    ));
    out.push_str("zoom  changed  changed_MB  added  added_MB  deleted\n");
    for (z, zoom) in &report.zooms {
        if zoom.changed == 0 && zoom.added == 0 && zoom.deleted == 0 {
            continue;
        }
        out.push_str(&format!(
            "z{z:<3} {:>7} {:>10.1} {:>6} {:>8.1} {:>7}\n",
            zoom.changed,
            zoom.changed_bytes as f64 / 1e6,
            zoom.added,
            zoom.added_bytes as f64 / 1e6,
            zoom.deleted,
        ));
    }
    out.push_str("sidecar kind: old_MB -> new_MB (delta_MB)\n");
    for (kind, old_len, new_len) in &report.sidecars {
        let delta = *new_len as i64 - *old_len as i64;
        out.push_str(&format!(
            "kind {kind:<2}: {:.1} -> {:.1} ({:+.1})\n",
            *old_len as f64 / 1e6,
            *new_len as f64 / 1e6,
            delta as f64 / 1e6,
        ));
    }
    out.push_str(&format!(
        "overlay estimate: {:.3} GB of tile bodies (distinct stored bodies; index overhead not included)\n",
        report.overlay_bytes as f64 / 1e9,
    ));
    out.push_str(&format!("OVERLAY_BYTES={}\n", report.overlay_bytes));
    out
}
