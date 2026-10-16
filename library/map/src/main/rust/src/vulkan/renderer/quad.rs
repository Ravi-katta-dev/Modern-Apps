//! The shared overlay quad: unit-square vertices + indices, uploaded once.
//!
//! Split from `mod.rs` (file-length limit). `Renderer::new` uploads these; every
//! screen-anchored overlay (puck, markers, vehicles, scrim, Moon disc, id-buffer pick)
//! draws from them, so the per-frame cost is one push constant and one draw, not an
//! allocation.
//!
//! Items are `pub` (not `pub(super)`) so sibling passes can name `quad::Quad`,
//! `quad::QUAD_VERTICES` and `quad::QUAD_INDICES` directly; the parent keeps a private
//! `use` for `super::QUAD_INDICES` paths.

use crate::vulkan::buffers::Buffer;

/// The unit quad every screen-anchored overlay draws with: four vertices in −1..1 and the
/// two triangles over them.
///
/// Uploaded once in [`Renderer::new`](super::Renderer::new), because everything that
/// varies about an overlay — where it is, how big, what colour, which way it points —
/// rides in the matrix and the push constants. So the per-frame cost is one
/// `cmd_push_constants` and one `cmd_draw_indexed`, not the pair of `vkAllocateMemory`
/// calls a transient buffer pays.
pub struct Quad {
    pub vertices: Buffer,
    pub indices: Buffer,
}

/// The four corners of the unit square, in the −1..1 the puck shaders read as a local
/// coordinate. Three floats per corner to match the shared fill stride (the puck vertex
/// shader reads only the first two; the z is 0.0 and ignored).
pub const QUAD_VERTICES: [f32; 12] = [
    -1.0, -1.0, 0.0, 1.0, -1.0, 0.0, 1.0, 1.0, 0.0, -1.0, 1.0, 0.0,
];
pub const QUAD_INDICES: [u32; 6] = [0, 1, 2, 0, 2, 3];
