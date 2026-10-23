//! Lane turn arrows: per-frame triangle builds through the fill pipeline.
//
// File-length split from `record_extra.rs`: `record_arrows` and its `draw_fill_batch`
// helper live here so that file stays under the 500-line lint. The sequence itself is
// unchanged.
use super::fog::tile_offscreen;
use super::bind_state::BindState;
use super::{argb_to_rgba, Renderer, ARROW_COLOR, ARROW_DP};
use crate::camera::Camera;
use crate::style::Layer;
use crate::vulkan::pipeline::{Push, MORPH_NONE};
use ash::vk;

impl Renderer {
    /// Draw the per-lane turn arrows: one glyph per marked lane, bent by the manoeuvre the lane
    /// leads into and pushed sideways onto the lane the carriageway painted.
    ///
    /// Built per frame — rotation, screen size and lane offset all follow the camera, exactly as
    /// the carriageway's width does — and drawn through the **fill** pipeline as plain coloured
    /// triangles, so it needs no glyph atlas and no new pipeline. Gated to the carriageway layer's
    /// zoom floor (z16), so below it nothing is built or drawn.
    pub(super) unsafe fn record_arrows(
        &mut self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        layers: &[Layer],
        submitted: &mut usize,
        culled: &mut usize,
        binds: &mut BindState,
    ) {
        // The carriageway layer, whose zoom window gates the arrows and whose width ramp puts them
        // over the lanes its own markings drew. Resolved through
        // [`crate::style::road_carriageway_layer`], which is careful to pick the *road* layer and
        // not the first layer that happens to carry a spread — see its doc comment.
        let Some(carriageway) = crate::style::road_carriageway_layer(layers) else {
            return;
        };
        let floor = camera.zoom.floor().clamp(0.0, 22.0) as u8;
        if !carriageway.draws_at(floor) {
            return;
        }
        let density = camera.density;
        // One lane of the carriageway, in device px. The lane count is the arrow's own, so a
        // three-lane approach and a five-lane one are each spread across their whole road.
        let lane_px = carriageway.width.at(camera.zoom) * density;
        // Every glyph tessellates to the same vertex count whatever angle it bends through, so a
        // tile's buffer is sized exactly once up front and never grows.
        let verts_per_arrow = crate::tile::arrow::ARROW_VERTS * 2;
        // Build each tile's triangles first — this borrows `self.tiles` — then upload and draw,
        // which takes `&mut self`. One batch per tile carries its own tile-to-clip matrix.
        let mut batches: Vec<([f32; 16], Vec<f32>)> = Vec::new();
        for tile in self.tiles.values() {
            if tile.arrows.is_empty() {
                continue;
            }
            // Covered-stand-in suppression, like the traffic pass: arrows sit
            // on the suppressed carriageway's lanes, so they suppress with it.
            if self
                .suppressed_tile(tile.z, tile.x, tile.y)
                .is_some_and(|suppressed| suppressed.arrows)
            {
                *culled += tile.arrows.len();
                continue;
            }
            // Same off-screen cull as the flat layer loop (see `tile_offscreen`).
            if tile_offscreen(camera, tile.z, tile.x, tile.y) {
                *culled += tile.arrows.len();
                continue;
            }
            let span = camera.tile_span_px(tile.z);
            if span <= 0.0 {
                continue;
            }
            let scale = ARROW_DP * density / span; // tile-local 0..1 units per unit-arrow coord
                                                   // What turns the arrows' ground setback into this tile's units. A property of the tile
                                                   // and not of the camera, so the arrows stay the same distance behind the junction at
                                                   // every zoom — see `arrow::tile_local_per_metre`.
            let per_metre = crate::tile::arrow::tile_local_per_metre(tile.z, tile.y);
            let mut verts: Vec<f32> = Vec::with_capacity(tile.arrows.len() * verts_per_arrow);
            for a in &tile.arrows {
                // Sideways onto the lane, perpendicular to the road heading (not the glyph's
                // turn). `lane_centre` measures from the middle of the road out, in lane widths,
                // and already accounts for the half of the carriageway this direction occupies
                // under the tile's driving convention — an arrow that does not sit between the
                // dividers the markings drew is worse than no arrow at all.
                let lateral = crate::tile::arrow::lane_centre(a) * lane_px;
                crate::tile::arrow::arrow_verts(a, scale, lateral / span, per_metre, &mut verts);
            }
            if !verts.is_empty() {
                batches.push((camera.tile_to_clip(tile.z, tile.x, tile.y), verts));
            }
        }
        if batches.is_empty() {
            return;
        }
        let color = argb_to_rgba(ARROW_COLOR);
        for (tile_to_clip, verts) in batches {
            let indices: Vec<u32> = (0..(verts.len() / 2) as u32).collect();
            let push = Push {
                tile_to_clip,
                color,
                line: [0.0; 4],
                misc: [0.0, 0.0, 0.0, camera.time_seconds],
                morph: MORPH_NONE,
            };
            self.draw_fill_batch(command_buffer, &verts, &indices, &push, submitted, binds);
        }
    }

    /// Draw one batch through the fill pipeline (colour from the push constant, no descriptor
    /// set), suballocating its vertices and indices from this frame's [`ScratchRing`].
    ///
    /// The arrow twin of [`draw_symbol_batch`], and now allocates the same way. It previously
    /// created two `Buffer`s per call and pushed them onto [`Renderer::transients`] to retire
    /// on the frames-in-flight grace count, which was merely wasteful while it was the symbol
    /// path's twin — but it is called **once per batch per frame** from
    /// [`record_arrows`](Self::record_arrows), and `transients` is a wake reason for the host's
    /// on-demand frame loop (see [`needs_frame`](Self::needs_frame)). A vec refilled every
    /// frame never drains, so the loop would wake itself forever and pin the map at 60fps with
    /// nothing changing. The ring has no such feedback: it is reset wholesale against the
    /// frame fence, so a draw leaves nothing behind for the next frame to notice.
    ///
    /// Off the drawn path today — `style::LANE_RENDERING` is false, so no arrows are built or
    /// drawn — which is precisely why this had to be fixed now rather than when someone
    /// switches lane rendering on and finds the phone hot again.
    pub(super) unsafe fn draw_fill_batch(
        &mut self,
        command_buffer: vk::CommandBuffer,
        vertices: &[f32],
        indices: &[u32],
        push: &Push,
        submitted: &mut usize,
        binds: &mut BindState,
    ) {
        let frame_index = self.frame_index;
        let device = &self.context.device;
        // Disjoint field borrows, for the same reason `draw_symbol_batch` takes them: cloning
        // `ash::Device` copies its whole function-pointer table, and this is a per-draw path.
        let Some((vbuf, voffset)) = self.scratch[frame_index].push(
            &self.context.instance,
            self.context.physical_device,
            device,
            vertices,
        ) else {
            return;
        };
        let Some((ibuf, ioffset)) = self.scratch[frame_index].push(
            &self.context.instance,
            self.context.physical_device,
            device,
            indices,
        ) else {
            return;
        };
        // Arrows share the fill pipeline with the layer loop: usually already bound,
        // so consecutive arrow batches cost no bind at all.
        binds.bind_pipeline(device, command_buffer, self.pipelines.fill);
        device.cmd_push_constants(
            command_buffer,
            self.pipelines.layout,
            vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
            0,
            push.as_bytes(),
        );
        device.cmd_bind_vertex_buffers(command_buffer, 0, &[vbuf], &[voffset]);
        device.cmd_bind_index_buffer(command_buffer, ibuf, ioffset, vk::IndexType::UINT32);
        device.cmd_draw_indexed(command_buffer, indices.len() as u32, 1, 0, 0, 0);
        *submitted += 1;
    }
}
