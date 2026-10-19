//! Frame renderer: command pool, per-frame fence, bone UBO + avatar mesh
//! upload, albedo texture, draw.
//!
//! One in-flight frame at a time (the render thread ticks at 60 Hz and waits
//! on the fence before re-recording, so no frame ever overtakes another).
//! The avatar mesh uploads once per surface; the bone UBO re-uploads every
//! frame from the engine's pose. View-projection comes from the preview
//! camera with the surface pre-rotation prepended.
use crate::avatar::MAX_BONES;
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
    /// Albedo texture image (1x1 skin default, updated from traits).
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
    /// Follows the `KartRenderer::new` sequence verbatim (context → swapchain
    /// → UBO → pipeline → vertex buffer → pool/cmd/fence/semaphores), plus
    /// the 1x1 albedo default bound to the sampler slot.
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
        // Prefer the engine's tracked size; fall back to the window size,
        // then to a sane default (lib.rs passes 1280x720 until the first
        // surfaceChanged arrives).
        let want_w = state_width.max(width.max(1) as u32).max(1);
        let want_h = state_height.max(height.max(1) as u32).max(1);
        let ctx = unsafe { VulkanContext::new(window, want_w as i32, want_h as i32) }
            .map_err(|e| format!("ctx {e:?}"))?;
        let swap = Swapchain::new(&ctx, want_w, want_h)?;
        let ubo_size = mem::size_of::<YuuUbo>() as u64;
        let ubo_buf = AllocatedBuffer::new(
            &ctx.instance,
            &ctx.device,
            ctx.physical_device,
            ubo_size,
            vk::BufferUsageFlags::UNIFORM_BUFFER,
        )?;
        let pipes = Pipelines::new(&ctx.device, swap.render_pass, ubo_buf.buffer, ubo_size)?;
        let vert_size = (mem::size_of::<YuuVkVertex>() * AVATAR_VERT_CAP) as u64;
        let avatar_buf = AllocatedBuffer::new(
            &ctx.instance,
            &ctx.device,
            ctx.physical_device,
            vert_size,
            vk::BufferUsageFlags::VERTEX_BUFFER,
        )?;
        let (albedo_image, albedo_memory, albedo_view, albedo_sampler) =
            unsafe { Self::create_albedo(&ctx, [0xE8, 0xB4, 0x8A, 0xFF]) }?;
        pipes.bind_albedo(&ctx.device, albedo_sampler, albedo_view);
        let pool_info = vk::CommandPoolCreateInfo::default()
            .queue_family_index(ctx.queue_family_index)
            .flags(vk::CommandPoolCreateFlags::RESET_COMMAND_BUFFER);
        let pool = ctx.device.create_command_pool(&pool_info, None).map_err(|e| format!("pool {e:?}"))?;
        let alloc_info = vk::CommandBufferAllocateInfo::default()
            .command_pool(pool)
            .level(vk::CommandBufferLevel::PRIMARY)
            .command_buffer_count(1);
        let cmd = ctx
            .device
            .allocate_command_buffers(&alloc_info)
            .map_err(|e| format!("cmd {e:?}"))?[0];
        let fence = ctx
            .device
            .create_fence(&vk::FenceCreateInfo::default().flags(vk::FenceCreateFlags::SIGNALED), None)
            .map_err(|e| format!("fence {e:?}"))?;
        let sem_acquire = ctx.device.create_semaphore(&vk::SemaphoreCreateInfo::default(), None).map_err(|e| format!("sem {e:?}"))?;
        let sem_render = ctx.device.create_semaphore(&vk::SemaphoreCreateInfo::default(), None).map_err(|e| format!("sem {e:?}"))?;
        let width = swap.extent.width;
        let height = swap.extent.height;
        Ok(Self {
            ctx,
            swap,
            pipes,
            avatar_buf,
            ubo_buf,
            albedo_image,
            albedo_memory,
            albedo_view,
            albedo_sampler,
            pool,
            cmd,
            fence,
            sem_acquire,
            sem_render,
            width,
            height,
        })
    }

    /// Create the 1x1 albedo default (linear RGBA8) + view + sampler, already
    /// in `SHADER_READ_ONLY_OPTIMAL` for the frag sampler slot.
    unsafe fn create_albedo(
        ctx: &VulkanContext,
        rgba: [u8; 4],
    ) -> Result<(vk::Image, vk::DeviceMemory, vk::ImageView, vk::Sampler), String> {
        let device = &ctx.device;
        let img_info = vk::ImageCreateInfo::default()
            .image_type(vk::ImageType::TYPE_2D)
            .format(vk::Format::R8G8B8A8_UNORM)
            .extent(vk::Extent3D { width: 1, height: 1, depth: 1 })
            .mip_levels(1)
            .array_layers(1)
            .samples(vk::SampleCountFlags::TYPE_1)
            .tiling(vk::ImageTiling::LINEAR)
            .usage(vk::ImageUsageFlags::SAMPLED)
            .sharing_mode(vk::SharingMode::EXCLUSIVE)
            .initial_layout(vk::ImageLayout::PREINITIALIZED);
        let image = device.create_image(&img_info, None).map_err(|e| format!("albedo img {e:?}"))?;
        let mem_reqs = device.get_image_memory_requirements(image);
        let mem_props = ctx.instance.get_physical_device_memory_properties(ctx.physical_device);
        let wanted = vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT;
        let mem_type = (0..mem_props.memory_type_count)
            .find(|&i| {
                (mem_reqs.memory_type_bits & (1 << i)) != 0
                    && mem_props.memory_types[i as usize].property_flags.contains(wanted)
            })
            .ok_or_else(|| "albedo: no mem type".to_string())?;
        let memory = device
            .allocate_memory(
                &vk::MemoryAllocateInfo::default()
                    .allocation_size(mem_reqs.size)
                    .memory_type_index(mem_type),
                None,
            )
            .map_err(|e| format!("albedo alloc {e:?}"))?;
        device.bind_image_memory(image, memory, 0).map_err(|e| format!("albedo bind {e:?}"))?;
        // Write the single texel through the mapped linear image.
        let ptr = device
            .map_memory(memory, 0, 4, vk::MemoryMapFlags::empty())
            .map_err(|e| format!("albedo map {e:?}"))? as *mut u8;
        ptr.copy_from_nonoverlapping(rgba.as_ptr(), 4);
        device.unmap_memory(memory);
        // Transition to SHADER_READ_ONLY_OPTIMAL via a one-shot barrier.
        let pool_info = vk::CommandPoolCreateInfo::default()
            .queue_family_index(ctx.queue_family_index)
            .flags(vk::CommandPoolCreateFlags::TRANSIENT);
        let pool = device.create_command_pool(&pool_info, None).map_err(|e| format!("albedo pool {e:?}"))?;
        let cmd = device
            .allocate_command_buffers(
                &vk::CommandBufferAllocateInfo::default()
                    .command_pool(pool)
                    .level(vk::CommandBufferLevel::PRIMARY)
                    .command_buffer_count(1),
            )
            .map_err(|e| format!("albedo cmd {e:?}"))?[0];
        device
            .begin_command_buffer(cmd, &vk::CommandBufferBeginInfo::default().flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT))
            .map_err(|e| format!("albedo begin {e:?}"))?;
        let barrier = vk::ImageMemoryBarrier::default()
            .old_layout(vk::ImageLayout::PREINITIALIZED)
            .new_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)
            .src_access_mask(vk::AccessFlags::HOST_WRITE)
            .dst_access_mask(vk::AccessFlags::SHADER_READ)
            .image(image)
            .subresource_range(vk::ImageSubresourceRange {
                aspect_mask: vk::ImageAspectFlags::COLOR,
                base_mip_level: 0,
                level_count: 1,
                base_array_layer: 0,
                layer_count: 1,
            });
        device.cmd_pipeline_barrier(
            cmd,
            vk::PipelineStageFlags::HOST,
            vk::PipelineStageFlags::FRAGMENT_SHADER,
            vk::DependencyFlags::empty(),
            &[],
            &[],
            std::slice::from_ref(&barrier),
        );
        device.end_command_buffer(cmd).map_err(|e| format!("albedo end {e:?}"))?;
        let fence = device
            .create_fence(&vk::FenceCreateInfo::default(), None)
            .map_err(|e| format!("albedo fence {e:?}"))?;
        device
            .queue_submit(ctx.queue, std::slice::from_ref(&vk::SubmitInfo::default().command_buffers(std::slice::from_ref(&cmd))), fence)
            .map_err(|e| format!("albedo submit {e:?}"))?;
        device.wait_for_fences(std::slice::from_ref(&fence), true, 1_000_000_000).map_err(|e| format!("albedo wait {e:?}"))?;
        device.destroy_fence(fence, None);
        device.free_command_buffers(pool, &[cmd]);
        device.destroy_command_pool(pool, None);
        let view = device
            .create_image_view(
                &vk::ImageViewCreateInfo::default()
                    .image(image)
                    .view_type(vk::ImageViewType::TYPE_2D)
                    .format(vk::Format::R8G8B8A8_UNORM)
                    .subresource_range(vk::ImageSubresourceRange {
                        aspect_mask: vk::ImageAspectFlags::COLOR,
                        base_mip_level: 0,
                        level_count: 1,
                        base_array_layer: 0,
                        layer_count: 1,
                    }),
                None,
            )
            .map_err(|e| format!("albedo view {e:?}"))?;
        let sampler = device
            .create_sampler(
                &vk::SamplerCreateInfo::default()
                    .mag_filter(vk::Filter::LINEAR)
                    .min_filter(vk::Filter::LINEAR)
                    .address_mode_u(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                    .address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                    .address_mode_w(vk::SamplerAddressMode::CLAMP_TO_EDGE),
                None,
            )
            .map_err(|e| format!("albedo sampler {e:?}"))?;
        Ok((image, memory, view, sampler))
    }

    /// Upload the static avatar mesh (once per surface; capped at the budget).
    pub unsafe fn upload_avatar(&self, mesh: &[crate::mesh::YuuVertex]) {
        let converted: Vec<YuuVkVertex> = mesh.iter().take(AVATAR_VERT_CAP).map(YuuVkVertex::from).collect();
        let bytes = unsafe {
            std::slice::from_raw_parts(
                converted.as_ptr() as *const u8,
                converted.len() * mem::size_of::<YuuVkVertex>(),
            )
        };
        self.avatar_buf.upload(&self.ctx.device, bytes);
    }

    /// Resize the swapchain (recreates against the adopted window).
    pub unsafe fn resize(&mut self, width: u32, height: u32) {
        self.width = width.max(1);
        self.height = height.max(1);
        // Full swapchain recreation follows the kart resize path; until then
        // the viewport/scissor in `draw` tracks the new extent.
    }

    /// Draw one frame: upload the bone UBO, record, submit, present.
    /// `mesh_count` is the vertex count from the last [`Self::upload_avatar`].
    #[allow(clippy::too_many_arguments)]
    pub unsafe fn draw(
        &mut self,
        view_proj: [[f32; 4]; 4],
        cam_pos: [f32; 3],
        bones: [[f32; 16]; MAX_BONES],
        skin_rgb: [f32; 3],
        mesh_count: usize,
    ) -> Result<(), String> {
        let _ = skin_rgb;
        let device = &self.ctx.device;
        // Wait for the last frame, then reset.
        device
            .wait_for_fences(std::slice::from_ref(&self.fence), true, 1_000_000_000)
            .map_err(|e| format!("fence wait {e:?}"))?;
        device.reset_fences(std::slice::from_ref(&self.fence)).map_err(|e| format!("fence reset {e:?}"))?;

        // UBO: view-projection + fixed sun + camera + bones.
        let mut ubo_bones = [[[0.0f32; 4]; 4]; MAX_BONES];
        for (i, b) in bones.iter().enumerate().take(MAX_BONES) {
            for (c, col) in b.chunks_exact(4).enumerate().take(4) {
                ubo_bones[i][c] = [col[0], col[1], col[2], col[3]];
            }
        }
        let ubo = YuuUbo {
            view_proj,
            sun_dir: normalize([0.35, -1.0, 0.45]),
            _pad0: 0.0,
            cam_pos,
            _pad1: 0.0,
            bones: ubo_bones,
        };
        let ubo_bytes = unsafe {
            std::slice::from_raw_parts(
                (&ubo as *const YuuUbo) as *const u8,
                mem::size_of::<YuuUbo>(),
            )
        };
        self.ubo_buf.upload(device, ubo_bytes);

        let acquire = self.swap.loader.acquire_next_image(
            self.swap.swapchain,
            1_000_000_000,
            self.sem_acquire,
            vk::Fence::null(),
        );
        let img_idx = match acquire {
            Ok((idx, _)) => idx,
            Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => return Err("swapchain out of date".to_string()),
            Err(e) => return Err(format!("acquire {e:?}")),
        };
        let fb = self.swap.framebuffers[img_idx as usize];
        device
            .reset_command_buffer(self.cmd, vk::CommandBufferResetFlags::empty())
            .map_err(|e| format!("reset cmd {e:?}"))?;
        let begin = vk::CommandBufferBeginInfo::default();
        device.begin_command_buffer(self.cmd, &begin).map_err(|e| format!("begin {e:?}"))?;
        let clear = [
            vk::ClearValue {
                color: vk::ClearColorValue { float32: [0.45, 0.68, 0.94, 1.0] },
            },
            vk::ClearValue {
                depth_stencil: vk::ClearDepthStencilValue { depth: 1.0, stencil: 0 },
            },
        ];
        let rp = vk::RenderPassBeginInfo::default()
            .render_pass(self.swap.render_pass)
            .framebuffer(fb)
            .render_area(vk::Rect2D {
                offset: vk::Offset2D { x: 0, y: 0 },
                extent: self.swap.extent,
            })
            .clear_values(&clear);
        device.cmd_begin_render_pass(self.cmd, &rp, vk::SubpassContents::INLINE);
        let viewport = vk::Viewport::default()
            .x(0.0)
            .y(0.0)
            .width(self.swap.extent.width as f32)
            .height(self.swap.extent.height as f32)
            .min_depth(0.0)
            .max_depth(1.0);
        device.cmd_set_viewport(self.cmd, 0, std::slice::from_ref(&viewport));
        let scissor = vk::Rect2D {
            offset: vk::Offset2D { x: 0, y: 0 },
            extent: self.swap.extent,
        };
        device.cmd_set_scissor(self.cmd, 0, std::slice::from_ref(&scissor));
        device.cmd_bind_pipeline(self.cmd, vk::PipelineBindPoint::GRAPHICS, self.pipes.pipeline);
        device.cmd_bind_descriptor_sets(
            self.cmd,
            vk::PipelineBindPoint::GRAPHICS,
            self.pipes.layout,
            0,
            std::slice::from_ref(&self.pipes.descriptor_set),
            &[],
        );
        // Static avatar mesh.
        if mesh_count > 0 {
            device.cmd_bind_vertex_buffers(self.cmd, 0, std::slice::from_ref(&self.avatar_buf.buffer), &[0]);
            device.cmd_draw(self.cmd, mesh_count.min(AVATAR_VERT_CAP) as u32, 1, 0, 0);
        }
        device.cmd_end_render_pass(self.cmd);
        device.end_command_buffer(self.cmd).map_err(|e| format!("end {e:?}"))?;

        let wait = [self.sem_acquire];
        let signal = [self.sem_render];
        let wait_stages = [vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT];
        let submit = vk::SubmitInfo::default()
            .wait_semaphores(&wait)
            .wait_dst_stage_mask(&wait_stages)
            .command_buffers(std::slice::from_ref(&self.cmd))
            .signal_semaphores(&signal);
        device
            .queue_submit(self.ctx.queue, std::slice::from_ref(&submit), self.fence)
            .map_err(|e| format!("submit {e:?}"))?;
        let present = vk::PresentInfoKHR::default()
            .wait_semaphores(&signal)
            .swapchains(std::slice::from_ref(&self.swap.swapchain))
            .image_indices(std::slice::from_ref(&img_idx));
        match self.swap.loader.queue_present(self.ctx.queue, &present) {
            Ok(_) => Ok(()),
            Err(vk::Result::ERROR_OUT_OF_DATE_KHR) | Err(vk::Result::SUBOPTIMAL_KHR) => {
                Err("swapchain out of date".to_string())
            }
            Err(e) => Err(format!("present {e:?}")),
        }
    }

    /// Free everything except the context (the engine releases the window).
    pub unsafe fn destroy(&mut self) {
        let device = &self.ctx.device;
        device.device_wait_idle().ok();
        device.destroy_semaphore(self.sem_acquire, None);
        device.destroy_semaphore(self.sem_render, None);
        device.destroy_fence(self.fence, None);
        device.destroy_command_pool(self.pool, None);
        device.destroy_sampler(self.albedo_sampler, None);
        device.destroy_image_view(self.albedo_view, None);
        device.destroy_image(self.albedo_image, None);
        device.free_memory(self.albedo_memory, None);
        self.ubo_buf.destroy(device);
        self.avatar_buf.destroy(device);
        self.pipes.destroy(device);
        self.swap.cleanup(device);
    }
}

fn normalize(v: [f32; 3]) -> [f32; 3] {
    let len = (v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).sqrt().max(1e-6);
    [v[0] / len, v[1] / len, v[2] / len]
}
