//! Per-frame bind-state tracking: skip redundant pipeline + descriptor binds.
//!
//! MapLibre batches draws by program so the GPU sees one state change per group, not one per
//! draw. The record path here issues draws tile-by-tile and pass-by-pass, and several passes
//! re-bind the same pipeline (or the same atlas set) dozens of times a frame: fill/line draws
//! when the kind flips back and forth across layers, the traffic line draws after the layer
//! loop's lines, every `draw_symbol_batch` rebinding the glyph/sprite set it already bound.
//!
//! [`BindState`] fixes that without reordering any draw: it remembers the last-bound pipeline
//! and descriptor set for the frame, and its `bind_*` helpers issue the Vulkan call only on a
//! change. Draw order — the painter's-algorithm contract on `record_inner` — is untouched, so
//! this can only remove redundant state changes, never visible ones. Every real bind is
//! counted into `Step::PipelineBinds` for the debug overlay.
//!
//! Split out (file-length limit); the call sites live in `record*.rs`.
use super::Renderer;
use crate::style::LayerKind;
use crate::timing::Step;
use ash::vk;

/// Which pipeline + descriptor set the command buffer currently has bound.
///
/// Reset at the top of every frame (a fresh command buffer binds nothing). The pipeline key
/// is the raw handle plus the kind that selected it: `LayerKind` alone is not unique across
/// the flat/globe variants, and the handle alone would merge passes that must stay distinct
/// in the reader's eyes — belt and braces, since a missed bind draws wrong and a redundant
/// one only costs.
pub(super) struct BindState {
    pipeline: Option<vk::Pipeline>,
    /// The atlas set bound with the pipeline, if the pipeline takes one (symbol/icon/sprite).
    set: Option<vk::DescriptorSet>,
    /// Real binds issued this frame, for `Step::PipelineBinds`.
    binds: u64,
}

impl BindState {
    pub(super) fn new() -> BindState {
        BindState {
            pipeline: None,
            set: None,
            binds: 0,
        }
    }

    /// Bind `pipeline` (no descriptor set) when it differs from the current one.
    ///
    /// Returns whether a real bind was issued. The caller still owns the draw; this only
    /// owns the state tracking.
    pub(super) fn bind_pipeline(
        &mut self,
        device: &ash::Device,
        command_buffer: vk::CommandBuffer,
        pipeline: vk::Pipeline,
    ) -> bool {
        if self.pipeline == Some(pipeline) && self.set.is_none() {
            return false;
        }
        unsafe {
            device.cmd_bind_pipeline(command_buffer, vk::PipelineBindPoint::GRAPHICS, pipeline);
        }
        self.pipeline = Some(pipeline);
        self.set = None;
        self.binds += 1;
        true
    }

    /// Bind `pipeline` with atlas descriptor `set` when either differs.
    ///
    /// Symbol/icon/sprite draws always pair a pipeline with its atlas; tracking both means
    /// a text batch following an icon batch with the same set but a different pipeline still
    /// rebinds the pipeline, while identical pairs skip both calls.
    pub(super) fn bind_pipeline_with_set(
        &mut self,
        device: &ash::Device,
        command_buffer: vk::CommandBuffer,
        symbol_layout: vk::PipelineLayout,
        pipeline: vk::Pipeline,
        set: vk::DescriptorSet,
    ) -> bool {
        if self.pipeline == Some(pipeline) && self.set == Some(set) {
            return false;
        }
        unsafe {
            device.cmd_bind_pipeline(command_buffer, vk::PipelineBindPoint::GRAPHICS, pipeline);
            device.cmd_bind_descriptor_sets(
                command_buffer,
                vk::PipelineBindPoint::GRAPHICS,
                symbol_layout,
                0,
                &[set],
                &[],
            );
        }
        self.pipeline = Some(pipeline);
        self.set = Some(set);
        // One pipeline bind + one set bind saved or spent together: count the pair as one
        // so the overlay row tracks state changes, not Vulkan calls.
        self.binds += 1;
        true
    }

    /// Flush the frame's bind count into the step timers. Called once per recorded frame
    /// (not on early-out skips, which bind nothing).
    pub(super) fn flush(&self, renderer: &Renderer) {
        renderer
            .step_times
            .borrow_mut()
            .record(Step::PipelineBinds, self.binds);
    }
}

/// Map a flat layer kind to its frame pipeline, globe-aware.
///
/// The match that used to sit inline in the layer loop: one place that names the
/// fill/line(+globe) selection, so the loop's `BindState` call reads as a lookup plus a bind.
pub(super) fn flat_pipeline(
    renderer: &Renderer,
    kind: LayerKind,
    globe: bool,
) -> Option<vk::Pipeline> {
    match kind {
        LayerKind::Fill if globe => Some(renderer.pipelines.fill_globe),
        LayerKind::Fill => Some(renderer.pipelines.fill),
        LayerKind::Line if globe => Some(renderer.pipelines.line_globe),
        LayerKind::Line => Some(renderer.pipelines.line),
        // Symbols never take the flat path (deferred to `record_symbol`); the layer loop
        // skips them before reaching here, so this arm is unreachable but keeps the
        // match exhaustive without panicking.
        LayerKind::Symbol => None,
    }
}
