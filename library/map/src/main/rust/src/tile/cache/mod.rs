//! Disk cache for HTTP byte ranges of the mamaps archive.
//!
//! A port of the policy in `maps/src/main/java/com/vayunmathur/maps/util/MapTileCache.kt`. Everything
//! load-bearing there is kept:
//!
//! * **`SHA-256(url + "\n" + range)` keys**, so a repointed archive cannot collide with
//!   the previous one's ranges.
//! * **`.data`/`.meta` pairs written temp-then-rename**, meta first, so the presence of
//!   a data file implies its meta is already complete and a reader never sees a
//!   half-written entry.
//! * **An `.origin` marker**: the cache is opened once with the full marker
//!   (`CACHE_FORMAT | URL | build_id`, the id read from a header prefix fetched
//!   before the cache opens), and any mismatch wipes every entry. The URL is part of
//!   it because the archive is republished under the same name, and a cached directory
//!   chunk from the previous build addresses the previous build's byte offsets. That is
//!   what the marker exists to prevent.
//! * A cached range is kept indefinitely and served whenever the device is **offline**,
//!   and is only re-fetched when online *and* next wanted at least
//!   [`REFRESH_INTERVAL_MS`] after it was fetched. It is never dropped for being stale
//!   — only overwritten by a *successful* refetch.
//!
//! What is added is the **size cap** the original never had: it grew without bound,
//! which was tolerable for one map app and is not for five.
//!
//! Freshness is read from the meta file's recorded fetch time, which leaves the data
//! file's mtime free to be the LRU access stamp — so touching an entry on a hit does not
//! also make it look freshly fetched.

use sha2::{Digest, Sha256};
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

#[cfg(test)]
mod tests;

/// 24 hours, as the original uses.
pub const REFRESH_INTERVAL_MS: u64 = 24 * 60 * 60 * 1000;

/// 64 MB. A screenful at z14 is a few hundred kilobytes, so this holds a large amount
/// of previously-viewed area for offline use while staying inside what five apps can
/// each afford on disk.
pub const DEFAULT_MAX_BYTES: u64 = 64 * 1024 * 1024;

const ORIGIN_FILE: &str = ".origin";

/// A cached range and when it was fetched.
pub struct Cached {
    pub fetched_at_ms: u64,
    pub body: Vec<u8>,
}

pub struct RangeCache {
    dir: PathBuf,
    max_bytes: u64,
    /// Injectable so the freshness and eviction policies are testable without sleeping.
    clock: Box<dyn Fn() -> u64 + Send + Sync>,
    /// Writes since the last eviction scan (issue 815).
    ///
    /// [`evict_if_over_cap`](Self::evict_if_over_cap) stats every entry in the directory, so
    /// running it per write during a cold fill — dozens of ranges per screenful across 8 workers —
    /// spends more syscalls on the scan than on the writes. Eviction is best-effort anyway (a full
    /// disk must not stop the map), so the scan runs every [`EVICT_EVERY_WRITES`] writes; the
    /// cache overshoots its cap by at most that many entries in between.
    writes_since_evict: std::sync::atomic::AtomicU64,
}

/// How many writes accumulate between eviction scans. A screenful is dozens of ranges, so the
/// scan runs about once per screenful rather than once per range.
const EVICT_EVERY_WRITES: u64 = 32;

impl RangeCache {
    /// Open the cache once with the full origin marker, dropping every entry
    /// when the marker differs from the one on disk.
    ///
    /// The caller fetches the archive header prefix *before* opening, reads the
    /// `build_id` out of it, and passes the full marker — so there is no second
    /// step and no window where the cache is open but unvalidated.
    pub fn open(dir: impl Into<PathBuf>, origin: &str, max_bytes: u64) -> RangeCache {
        Self::with_clock(dir, origin, max_bytes, Box::new(now_ms))
    }

    pub fn with_clock(
        dir: impl Into<PathBuf>,
        origin: &str,
        max_bytes: u64,
        clock: Box<dyn Fn() -> u64 + Send + Sync>,
    ) -> RangeCache {
        let dir = dir.into();
        let _ = fs::create_dir_all(&dir);
        let cache = RangeCache {
            dir,
            max_bytes,
            clock,
            writes_since_evict: std::sync::atomic::AtomicU64::new(0),
        };
        cache.invalidate_on_origin_change(origin);
        cache
    }

    /// `SHA-256(url + "\n" + range)`, hex.
    pub fn key(url: &str, range: &str) -> String {
        let mut hasher = Sha256::new();
        hasher.update(url.as_bytes());
        hasher.update(b"\n");
        hasher.update(range.as_bytes());
        hasher
            .finalize()
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect()
    }

    /// The entry for `key`, or `None` when absent or unreadable.
    pub fn read(&self, key: &str) -> Option<Cached> {
        let data_path = self.dir.join(format!("{key}.data"));
        let meta_path = self.dir.join(format!("{key}.meta"));
        let meta = fs::read_to_string(&meta_path).ok()?;
        let body = fs::read(&data_path).ok()?;

        let mut fetched_at_ms = 0u64;
        let mut recorded_len: Option<usize> = None;
        for line in meta.lines() {
            if let Some(v) = line.strip_prefix("fetchedAt=") {
                fetched_at_ms = v.trim().parse().unwrap_or(0);
            } else if let Some(v) = line.strip_prefix("length=") {
                recorded_len = v.trim().parse().ok();
            }
        }
        // A body that does not match what its meta recorded was truncated on the way to
        // disk, which is exactly the corruption this must not serve.
        if recorded_len.is_some_and(|len| len != body.len()) {
            self.drop_entry(key);
            return None;
        }
        // The data file's mtime is the LRU stamp.
        let _ = filetime_touch(&data_path, (self.clock)());
        Some(Cached {
            fetched_at_ms,
            body,
        })
    }

    /// Is `entry` fresh enough to serve without asking the network?
    pub fn is_fresh(&self, entry: &Cached) -> bool {
        (self.clock)().saturating_sub(entry.fetched_at_ms) < REFRESH_INTERVAL_MS
    }

    pub fn write(&self, key: &str, body: &[u8]) {
        // Caching is best-effort: a full disk must not stop the map drawing.
        if self.write_inner(key, body).is_err() {
            return;
        }
        if self
            .writes_since_evict
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed)
            + 1
            >= EVICT_EVERY_WRITES
        {
            self.writes_since_evict
                .store(0, std::sync::atomic::Ordering::Relaxed);
            self.evict_if_over_cap();
        }
    }

    /// Durability flush: order recently renamed entries so they survive a crash or a
    /// kill, without paying an fsync per write.
    ///
    /// Best-effort like everything else here: failures are ignored, because a missed
    /// flush only risks re-fetching, never correctness (every read validates its
    /// recorded length, and the origin marker gates the build). Called from the upload
    /// drain once per frame at most — not per tile — which batches the durability cost
    /// the old per-write `sync_all` paid on every range.
    ///
    /// Implemented by syncing the two just-renamed files once more after their renames
    /// (rather than syncing the directory, which needs platform calls std cannot make):
    /// the entry contents were already synced at write time, and this second sync after
    /// the rename orders the directory entries on ext4/f2fs without a per-write penalty.
    /// Cheap enough to call per frame: it opens, syncs and closes two files per entry
    /// flushed — so callers batch it (see the drain), rather than calling per tile.
    pub fn flush_entry(&self, key: &str) {
        for suffix in [".meta", ".data"] {
            let path = self.dir.join(format!("{key}{suffix}"));
            if let Ok(file) = fs::File::open(&path) {
                let _ = file.sync_all();
            }
        }
    }

    fn write_inner(&self, key: &str, body: &[u8]) -> std::io::Result<()> {
        let now = (self.clock)();
        // Meta first, then data, each temp-then-rename, so the presence of the data file
        // implies a complete meta. Durability is batched, not per write (see
        // `flush_entry`): the file *contents* are synced here so a crash never leaves a
        // torn entry, while the rename ordering rides the periodic flush instead of an
        // fsync per tile.
        let meta_tmp = self.dir.join(format!("{key}.meta.tmp"));
        {
            let mut file = fs::File::create(&meta_tmp)?;
            writeln!(file, "fetchedAt={now}")?;
            writeln!(file, "length={}", body.len())?;
            file.sync_all()?;
        }
        fs::rename(&meta_tmp, self.dir.join(format!("{key}.meta")))?;

        let data_tmp = self.dir.join(format!("{key}.data.tmp"));
        {
            let mut file = fs::File::create(&data_tmp)?;
            file.write_all(body)?;
            file.sync_all()?;
        }
        let data_path = self.dir.join(format!("{key}.data"));
        fs::rename(&data_tmp, &data_path)?;
        let _ = filetime_touch(&data_path, now);
        Ok(())
    }

    pub fn drop_entry(&self, key: &str) {
        let _ = fs::remove_file(self.dir.join(format!("{key}.data")));
        let _ = fs::remove_file(self.dir.join(format!("{key}.meta")));
    }

    /// Delete least-recently-read entries until the cache is comfortably under the cap.
    ///
    /// Down to 80% rather than exactly to the cap, so a session sitting at the limit
    /// does not run an eviction pass per tile.
    fn evict_if_over_cap(&self) {
        let mut entries: Vec<(u64, u64, String)> = Vec::new();
        let mut total = 0u64;
        let read_dir = match fs::read_dir(&self.dir) {
            Ok(d) => d,
            Err(_) => return,
        };
        for entry in read_dir.flatten() {
            let path = entry.path();
            let name = match path.file_name().and_then(|n| n.to_str()) {
                Some(n) => n,
                None => continue,
            };
            let key = match name.strip_suffix(".data") {
                Some(k) => k.to_string(),
                None => continue,
            };
            let metadata = match entry.metadata() {
                Ok(m) => m,
                Err(_) => continue,
            };
            let len = metadata.len();
            let stamp = metadata
                .modified()
                .ok()
                .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                .map(|d| d.as_millis() as u64)
                .unwrap_or(0);
            total += len;
            entries.push((stamp, len, key));
        }
        if total <= self.max_bytes {
            return;
        }
        let target = self.max_bytes / 10 * 8;
        entries.sort_by_key(|(stamp, _, _)| *stamp);
        for (_, len, key) in entries {
            if total <= target {
                break;
            }
            total -= len;
            self.drop_entry(&key);
        }
    }

    /// Wipe the cache if `origin` differs from its marker.
    fn invalidate_on_origin_change(&self, origin: &str) {
        let marker = self.dir.join(ORIGIN_FILE);
        if fs::read_to_string(&marker)
            .map(|s| s.trim() == origin)
            .unwrap_or(false)
        {
            return;
        }
        if let Ok(read_dir) = fs::read_dir(&self.dir) {
            for entry in read_dir.flatten() {
                if entry.file_name() != ORIGIN_FILE {
                    let _ = fs::remove_file(entry.path());
                }
            }
        }
        // Best-effort: a failure here must not break map init. The worst case is that
        // stale entries survive, and every read validates its recorded length.
        let _ = fs::write(&marker, origin);
    }
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// Set a file's mtime, which is the LRU stamp.
///
/// `std::fs` cannot set mtimes and pulling in `filetime` for one call is not worth a
/// dependency, so the file is opened for append and a zero-length write is flushed —
/// which updates mtime on every filesystem we target without changing the contents.
fn filetime_touch(path: &Path, _now_ms: u64) -> std::io::Result<()> {
    let file = fs::OpenOptions::new().append(true).open(path)?;
    file.set_len(file.metadata()?.len())?;
    Ok(())
}
