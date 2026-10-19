//! Frame renderer: command pool, per-frame fence, bone UBO + avatar mesh
//! upload, albedo texture, draw.
//!
//! One in-flight frame at a time (the render thread ticks at 60 Hz and waits
//! on the fence before re-recording, so no frame ever overtakes another).
//! The avatar mesh uploads once per surface; the bone UBO re-uploads every
//! frame from the engine's pose. View-projection comes from the preview
//! camera with the surface pre-rotation prepended.
use crate::avatar::MAX_BONES;
use crate::mesh::YuuVertex;
use crate::vulkan::buffers::{AllocatedBuffer, YuuUbo, YuuVkVertex};
use crate::vulkan::context::VulkanContext;
use crate::vulkan::pipeline::Pipelines;
use crate::vulkan::swapchain::Swapchain;
use ash::vk;
use std::mem;

/// Static avatar mesh budget: the procedural fallback is ~1k verts; 64k
/// leaves margin for a detailed glTF base mesh.
const AVATAR_VERT_CAP: usize = 65_536;

/// Everything needed to draw one frame.
pub struct YuuRenderer {
    /// Vulkan context (owns instance/device/surface).
    pub ctx: VulkanContext,
    /// Swapchain + depth + render pass + framebuffers.
    pub swap: Swapchain,
    /// Graphics pipeline + UBO/sampler descriptor set.
    pub pipes: Pipelines,
    /// Static avatar vertex buffer (uploaded per surface).
    pub avatar_buf: AllocatedBuffer,
    /// Uniform buffer (view-projection + sun + camera + bones).
    pub ubo_buf: AllocatedBuffer,
    /// Albedo texture image + memory + view + sampler (1x1 skin default).
    pub albedo_image: vk::Image,
    /// Albedo memory.
    pub albedo_memory: vk::DeviceMemory,
    /// Albedo view.
    pub albedo_view: vk::ImageView,
    /// Albedo sampler.
    pub albedo_sampler: vk::Sampler,
    /// Command pool (graphics family).
    pub pool: vk::CommandPool,
    /// One reusable command buffer.
    pub cmd: vk::CommandBuffer,
    /// Frame fence (signalled when the GPU finishes the frame).
    pub fence: vk::Fence,
    /// Image-acquired + render-finished semaphores.
    pub sem_acquire: vk::Semaphore,
    /// Image-acquired + render-finished semaphores.
    pub sem_render: vk::Semaphore,
    /// Current surface width.
    pub width: u32,
    /// Current surface height.
    pub height: u32,
}

impl YuuRenderer {
    /// Create from a live window at `width` x `height`. Fails cleanly.
    ///
    /// # Safety
    /// `window` must be a live adopted `ANativeWindow`; ownership stays with
    /// the engine (released on destroy), as on the kart path.
    pub unsafe fn new(
        window: *mut crate::vulkan::context::ANativeWindow,
        width: i32,
        height: i32,
        state_width: u32,
        state_height: u32,
    ) -> Result<Self, String> {
        let _ = (window, width, height, state_width, state_height);
        // NOTE(size): full swapchain bring-up (Swapchain::new against the
        // adopted window, depth, render pass) follows the kart
        // `vulkan/renderer.rs::KartRenderer::new` sequence verbatim; this
        // scaffold lands the avatar-side types (buffers/pipeline/UBO) first so
        // `cargo check` validates the skinning pipeline before the backend
        // copy-paste lands. Until then, creation reports unavailable and the
        // Kotlin `isAvailable` fallback keeps the 2D preview.
        Err("yuu_avatar Vulkan backend: swapchain bring-up lands next".to_string())
    }

    /// Upload the static avatar mesh (once per surface; capped at the budget).
    pub unsafe fn upload_avatar(&self, _mesh: &[YuuVertex]) {
        // Filled in with the swapchain bring-up (see `new` note).
    }

    /// Resize the swapchain.
    pub unsafe fn resize(&mut self, width: u32, height: u32) {
        self.width = width.max(1);
        self.height = height.max(1);
    }

    /// Draw one frame: upload the bone UBO, record, submit, present.
    pub unsafe fn draw(
        &mut self,
        _view_proj: [[f32; 4]; 4],
        _cam_pos: [f32; 3],
        _bones: [[f32; 16]; MAX_BONES],
        _skin_rgb: [f32; 3],
        _mesh_count: usize,
    ) -> Result<(), String> {
        // Filled in with the swapchain bring-up (see `new` note).
        Ok(())
    }

    /// Free everything except the context (the engine releases the window).
    pub unsafe fn destroy(&mut self) {
        // Filled in with the swapchain bring-up (see `new` note).
        let _ = mem::size_of::<YuuUbo>();
        let _ = mem::size_of::<YuuVkVertex>();
        let _ = AVATAR_VERT_CAP;
    }
}
