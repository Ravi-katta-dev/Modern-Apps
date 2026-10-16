//! Pooled tile-upload staging: one reusable device allocation per pool, not per tile.
//!
//! A tile upload used to cost up to 5 fresh `vkAllocateMemory` pairs (flat, lines, ribbons,
//! buildings, terrain) on the Choreographer callback — and before format pooling, ~60-100.
//! `vkAllocateMemory` is a driver-global lock plus bookkeeping on most mobile drivers, so a
//! burst of finished tiles landing in one frame is the frame-time tail P0 measured.
//!
//! [`UploadPool`] fixes the remaining per-tile allocation: one persistent 8 MiB vertex arena
//! and one 4 MiB index arena, bump-allocated per upload and never freed until the renderer
//! dies. A tile's meshes pack into the arenas exactly as [`upload_packed`] packs them into
//! fresh buffers — same slices, same `first_index` rebasing — except the slices name
//! (arena, byte offset) ranges instead of whole buffers, and draws bind those offsets.
//!
//! Lifetime is the whole point, so it is stated plainly: arena memory is never reused for a
//! *different* tile. When an arena fills, the pool allocates the next arena (up to
//! [`MAX_ARENAS`]); a tile larger than one arena falls back to fresh buffers. Retired tiles
//! free nothing — their ranges simply leak until the renderer dies — which bounds the waste
//! to the residency cap (64 tiles × one upload each, amortised across arenas) instead of
//! paying an allocator round-trip per tile per upload. Tiles re-upload rarely (only on
//! generation changes), so the leak is cold, while the per-frame win is hot.
//!
//! Every fresh allocation the drain still issues (fallback path, first-fill growth) is
//! counted into `Step::UploadAllocs`: the overlay row reads zero in the steady state, and
//! any nonzero names the path to fix. Pooled uploads record nothing.
//!
//! Split out (file-length limit); `upload.rs` calls in, never the reverse.
use crate::vulkan::buffers::Buffer;
use crate::timing::Step;
use super::Renderer;
use ash::vk;

/// Vertex arena size: 8 MiB holds several dense z14 tiles' pooled vertices, so the steady
/// state is one arena for the session. Index arenas are half this.
const VERTEX_ARENA_BYTES: vk::DeviceSize = 8 * 1024 * 1024;
/// How many arenas the pool grows to before falling back to fresh buffers: enough for a
/// cold-start burst, few enough that a pathological session cannot eat the device.
const MAX_ARENAS: usize = 4;

/// One persistent upload arena pair, bump-allocated and never compacted.
///
/// `vertices`/`indices` are whole-arena buffers: tiles own *ranges* of them, never the
/// buffers, so `Drop`/`collect_retired` must never destroy these through a tile — only
/// [`destroy_pool`] destroys arenas, once, at renderer teardown.
struct Arena {
    vertices: Buffer,
    indices: Buffer,
    used_verts: vk::DeviceSize,
    used_indices: vk::DeviceSize,
}

/// The renderer's tile-upload pool: arena pairs plus the fresh-allocation counter.
///
/// Arena buffers are owned here and ONLY here: `upload.rs` borrows their handles for
/// draws and `collect_retired`/`Drop` consult them by handle comparison, but neither
/// ever destroys them — only [`destroy_pool`] does, once, at renderer teardown.
pub(super) struct UploadPool {
    pub(super) arenas: Vec<Arena>,
}

impl UploadPool {
    pub(super) fn new() -> UploadPool {
        UploadPool { arenas: Vec::new() }
    }

    /// Borrow one arena's buffer pair by index, for building a tile's draw binds.
    /// Null handles when the index names no arena — never panics on a stale slice.
    /// A null bind draws nothing, exactly as a missing pool did on the fresh path.
    pub(super) fn arena_buffers(&self, index: usize) -> (vk::Buffer, vk::Buffer) {
        self.arenas.get(index).map_or_else(
            || (vk::Buffer::null(), vk::Buffer::null()),
            |a| (a.vertices.buffer, a.indices.buffer),
        )
    }

    /// Whether `buffer` is an arena buffer owned by this pool (never destroyed through
    /// a tile) rather than a fresh per-tile buffer.
    ///
    /// Offset 0 is NOT the discriminator: the first tile in an arena starts at 0, so
    /// only the handle comparison is exact. Destroying an arena buffer through a tile
    /// would double-free it at renderer teardown.
    pub(super) fn owns_buffer(&self, b: vk::Buffer) -> bool {
        self.arenas
            .iter()
            .any(|a| a.vertices.buffer == b || a.indices.buffer == b)
    }

    /// Record fresh allocations into `Step::UploadAllocs`. Called by `upload.rs` for the
    /// fallback path and arena growth — pooled uploads record nothing, so the row reads
    /// zero in the steady state.
    pub(super) fn record_allocs(renderer: &Renderer, allocs: u64) {
        if allocs > 0 {
            renderer
                .step_times
                .borrow_mut()
                .record(Step::UploadAllocs, allocs);
        }
    }
}

/// One packed mesh group's slice of an arena: byte offsets into the arena pair plus the
/// per-mesh `firstIndex` list, in the same shape [`upload_packed`] returns.
pub(super) struct PooledPacked {
    /// Byte offset of this group's vertices in the arena's vertex buffer.
    pub vert_offset: vk::DeviceSize,
    /// Byte offset of this group's indices in the arena's index buffer.
    pub index_offset: vk::DeviceSize,
    /// Arena index holding this group.
    pub arena: usize,
    /// Per-mesh `firstIndex` values, rebased to absolute like [`upload_packed`].
    pub first_index: Vec<u32>,
}

/// Arena-backed slices for one tile's three packed groups.
pub(super) struct TileArenaSlices {
    pub flat: PooledPacked,
    pub lines: PooledPacked,
    pub ribbons: PooledPacked,
}

/// The per-tile mesh groups `upload` packs, in arena-commit order.
pub(super) struct UploadGroups<'a> {
    pub flat: &'a [(&'a [f32], &'a [u32])],
    pub lines: &'a [(&'a [f32], &'a [u32])],
    pub ribbons: &'a [(&'a [f32], &'a [u32])],
}

/// Byte sizes of one packed group's vertices and indices (f32/u32, 4 bytes each).
fn group_bytes(meshes: &[(&[f32], &[u32])]) -> (vk::DeviceSize, vk::DeviceSize) {
    let mut verts = 0usize;
    let mut indices = 0usize;
    for (v, i) in meshes {
        if i.is_empty() {
            continue;
        }
        verts += v.len();
        indices += i.len();
    }
    ((verts * 4) as vk::DeviceSize, (indices * 4) as vk::DeviceSize)
}

/// Rebase each mesh's slice offset, in input order — the same `firstIndex` list
/// [`upload_packed`] returns (packed *index* offsets), so draw slices line up with the
/// packed order. The per-mesh index *values* still need the vertex-base rebase at pack
/// time (see `pack_group`): the two rebases are the same pair `upload_packed` applies.
fn rebase_first_index(meshes: &[(&[f32], &[u32])]) -> Vec<u32> {
    let mut first_index = Vec::with_capacity(meshes.len());
    let mut packed_indices = 0u32;
    for (_, indices) in meshes {
        if indices.is_empty() {
            continue;
        }
        first_index.push(packed_indices);
        packed_indices += indices.len() as u32;
    }
    first_index
}

/// Concatenate one group's meshes and rebase index values by vertex base — the packed
/// bytes [`upload_packed`] would upload, returned instead of uploaded so the caller can
/// commit them into an arena.
fn pack_group(stride_floats: usize, meshes: &[(&[f32], &[u32])]) -> (Vec<f32>, Vec<u32>) {
    let mut packed_verts = Vec::new();
    let mut packed_indices = Vec::new();
    for (verts, indices) in meshes {
        if indices.is_empty() {
            continue;
        }
        let vertex_base = (packed_verts.len() / stride_floats) as u32;
        packed_verts.extend_from_slice(verts);
        packed_indices.extend(indices.iter().map(|i| i + vertex_base));
    }
    (packed_verts, packed_indices)
}

fn align64(v: vk::DeviceSize) -> vk::DeviceSize {
    (v + 63) & !63
}

/// Reserve arena space for one packed group; `None` when no arena fits.
///
/// `None` is not an error — the caller falls back to fresh buffers and counts the
/// allocation. Pure bump math, no Vulkan calls, so this never fails for device reasons.
/// A group larger than one arena never fits: fall back rather than splitting draws
/// across arenas, which would complicate every bind site for a case (a single mesh
/// group over 8 MiB) that never occurs.
fn try_reserve(
    pool: &UploadPool,
    meshes: &[(&[f32], &[u32])],
    vert_bytes: vk::DeviceSize,
    index_bytes: vk::DeviceSize,
) -> Option<PooledPacked> {
    if vert_bytes == 0 || index_bytes == 0 {
        return None;
    }
    if vert_bytes > VERTEX_ARENA_BYTES || index_bytes > VERTEX_ARENA_BYTES / 2 {
        return None;
    }
    for (n, arena) in pool.arenas.iter().enumerate() {
        let vo = align64(arena.used_verts);
        let io = align64(arena.used_indices);
        if vo + vert_bytes <= arena.vertices.size && io + index_bytes <= arena.indices.size {
            return Some(PooledPacked {
                vert_offset: vo,
                index_offset: io,
                arena: n,
                first_index: rebase_first_index(meshes),
            });
        }
    }
    if pool.arenas.len() >= MAX_ARENAS {
        return None;
    }
    Some(PooledPacked {
        vert_offset: 0,
        index_offset: 0,
        arena: pool.arenas.len(),
        first_index: rebase_first_index(meshes),
    })
}

/// Grow the pool by one arena pair (2 fresh allocations, counted by the caller).
fn grow_arenas(renderer: &mut Renderer) -> Result<(), String> {
    let instance = &renderer.context.instance;
    let physical = renderer.context.physical_device;
    let device = &renderer.context.device;
    // SAFETY: fresh buffers; owned by the pool and destroyed with the renderer.
    unsafe {
        let vertices = Buffer::upload(
            instance,
            physical,
            device,
            vk::BufferUsageFlags::VERTEX_BUFFER,
            &vec![0u8; VERTEX_ARENA_BYTES as usize],
        )?;
        let indices = match Buffer::upload(
            instance,
            physical,
            device,
            vk::BufferUsageFlags::INDEX_BUFFER,
            &vec![0u8; (VERTEX_ARENA_BYTES / 2) as usize],
        ) {
            Ok(b) => b,
            Err(e) => {
                vertices.destroy(device);
                return Err(e);
            }
        };
        renderer.upload_pool.arenas.push(Arena {
            vertices,
            indices,
            used_verts: 0,
            used_indices: 0,
        });
    }
    Ok(())
}

/// Copy packed bytes into a reserved arena range. The cursors advance even if a map
/// fails: the range is abandoned, never reused, so a failed copy cannot corrupt a later
/// tile — but the caller must then fall back rather than draw the range.
unsafe fn commit_bytes(
    renderer: &Renderer,
    packed: &PooledPacked,
    packed_verts: &[f32],
    packed_indices: &[u32],
) -> bool {
    let device = &renderer.context.device;
    let arena = &renderer.upload_pool.arenas[packed.arena];
    let vert_bytes = std::mem::size_of_val(packed_verts) as vk::DeviceSize;
    let index_bytes = std::mem::size_of_val(packed_indices) as vk::DeviceSize;
    // HOST_COHERENT arena memory: map, copy, unmap — no flush. Two maps per group; the
    // allocator calls they replace were the cost, not the maps.
    let ok_v = match device.map_memory(
        arena.vertices.memory,
        packed.vert_offset,
        vert_bytes,
        vk::MemoryMapFlags::empty(),
    ) {
        Ok(mapped) => {
            std::ptr::copy_nonoverlapping(
                packed_verts.as_ptr() as *const u8,
                mapped as *mut u8,
                vert_bytes as usize,
            );
            device.unmap_memory(arena.vertices.memory);
            true
        }
        Err(_) => false,
    };
    let ok_i = match device.map_memory(
        arena.indices.memory,
        packed.index_offset,
        index_bytes,
        vk::MemoryMapFlags::empty(),
    ) {
        Ok(mapped) => {
            std::ptr::copy_nonoverlapping(
                packed_indices.as_ptr() as *const u8,
                mapped as *mut u8,
                index_bytes as usize,
            );
            device.unmap_memory(arena.indices.memory);
            true
        }
        Err(_) => false,
    };
    ok_v && ok_i
}

fn advance_arena(pool: &mut UploadPool, packed: &PooledPacked, vert_bytes: u64, index_bytes: u64) {
    let arena = &mut pool.arenas[packed.arena];
    arena.used_verts = packed.vert_offset + vert_bytes;
    arena.used_indices = packed.index_offset + index_bytes;
}

/// Reserve one group's arena space, growing the pool once on a miss.
///
/// Returns `None` for an empty group (no reservation, no slices) and when the group
/// fits nowhere even after growth — the caller falls back for the whole tile. Growth
/// counts 2 into `Step::UploadAllocs` (one vertex + one index arena).
fn reserve_group(
    renderer: &mut Renderer,
    meshes: &[(&[f32], &[u32])],
    bytes: (vk::DeviceSize, vk::DeviceSize),
) -> Option<PooledPacked> {
    if bytes == (0, 0) {
        return None;
    }
    if let Some(reserved) = try_reserve(&renderer.upload_pool, meshes, bytes.0, bytes.1) {
        return Some(reserved);
    }
    grow_arenas(renderer).ok()?;
    UploadPool::record_allocs(renderer, 2);
    try_reserve(&renderer.upload_pool, meshes, bytes.0, bytes.1)
}

/// Commit one tile's three packed groups into the pool.
///
/// Reserves all three groups first (growing the pool if an empty arena is needed), then
/// packs and copies each group. Returns the arena slices, or `None` when any group did
/// not fit or any copy failed — the caller falls back to fresh buffers for the whole
/// tile. Mixing pooled and fresh slices per group would double the slice bookkeeping
/// for no steady-state gain, since the common case fits entirely.
///
/// Growth counts 2 into `Step::UploadAllocs` (one vertex + one index arena); a fully
/// pooled tile counts nothing.
///
/// Takes `&mut Renderer` (not `&Renderer`) because reserving may grow the pool: the
/// caller is `upload()`, which already holds `&mut self`.
pub(super) unsafe fn commit_tile_groups(
    renderer: &mut Renderer,
    groups: UploadGroups<'_>,
) -> Option<TileArenaSlices> {
    use crate::tess::{fill, ribbon, stroke};
    let flat_bytes = group_bytes(groups.flat);
    let line_bytes = group_bytes(groups.lines);
    let ribbon_bytes = group_bytes(groups.ribbons);
    // Reserve each group in turn; grow once on the first miss and retry that group; fail
    // the tile if it still misses. A group with no bytes reserves nothing.
    let flat = reserve_group(renderer, groups.flat, flat_bytes);
    let lines = reserve_group(renderer, groups.lines, line_bytes);
    let ribbons = reserve_group(renderer, groups.ribbons, ribbon_bytes);
    // Groups with no bytes reserved nothing: build their empty slices explicitly so the
    // tile below still carries all three groups. A group WITH bytes that reserved
    // nothing means the tile does not fit: fall back for the whole tile.
    let flat = match (flat_bytes, flat) {
        ((0, 0), _) => Some(PooledPacked {
            vert_offset: 0,
            index_offset: 0,
            arena: 0,
            first_index: Vec::new(),
        }),
        (_, r) => r,
    }?;
    let lines = match (line_bytes, lines) {
        ((0, 0), _) => Some(PooledPacked {
            vert_offset: 0,
            index_offset: 0,
            arena: 0,
            first_index: Vec::new(),
        }),
        (_, r) => r,
    }?;
    let ribbons = match (ribbon_bytes, ribbons) {
        ((0, 0), _) => Some(PooledPacked {
            vert_offset: 0,
            index_offset: 0,
            arena: 0,
            first_index: Vec::new(),
        }),
        (_, r) => r,
    }?;
    // Pack the bytes exactly as `upload_packed` does, then commit each non-empty group.
    let (fv, fi) = pack_group(fill::FLOATS_PER_VERTEX, groups.flat);
    let (lv, li) = pack_group(stroke::FLOATS_PER_VERTEX, groups.lines);
    let (rv, ri) = pack_group(ribbon::FLOATS_PER_VERTEX, groups.ribbons);
    if !fv.is_empty() && !commit_bytes(renderer, &flat, &fv, &fi) {
        return None;
    }
    if !lv.is_empty() && !commit_bytes(renderer, &lines, &lv, &li) {
        return None;
    }
    if !rv.is_empty() && !commit_bytes(renderer, &ribbons, &rv, &ri) {
        return None;
    }
    advance_arena(
        &mut renderer.upload_pool,
        &flat,
        fv.len() as u64 * 4,
        fi.len() as u64 * 4,
    );
    advance_arena(
        &mut renderer.upload_pool,
        &lines,
        lv.len() as u64 * 4,
        li.len() as u64 * 4,
    );
    advance_arena(
        &mut renderer.upload_pool,
        &ribbons,
        rv.len() as u64 * 4,
        ri.len() as u64 * 4,
    );
    Some(TileArenaSlices { flat, lines, ribbons })
}

/// Destroy every arena. Called from the renderer's `Drop`, after the device goes idle —
/// the same contract as every other buffer the renderer owns.
pub(super) unsafe fn destroy_pool(pool: &mut UploadPool, device: &ash::Device) {
    for arena in pool.arenas.drain(..) {
        arena.vertices.destroy(device);
        arena.indices.destroy(device);
    }
}
