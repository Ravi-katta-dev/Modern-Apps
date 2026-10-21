// Albedo texture: creation, linear-tiled upload helper, in-place re-upload.
//
// Split out of `renderer.rs` so each file stays under the 500-line Rust limit.
// Same module scope via `renderer.rs` (`include!`), so item order is irrelevant.

impl YuuRenderer {
    /// Create the ATLAS_SIZE×ATLAS_SIZE albedo (linear RGBA8) + view +
    /// sampler, already in `SHADER_READ_ONLY_OPTIMAL` for the frag sampler
    /// slot. Every texel is pre-filled with `rgba` (the skin tone), so if
    /// [`Self::upload_atlas`] is never called the avatar renders exactly as
    /// the old 1x1 skin-flat default did — same texel value under
    /// CLAMP_TO_EDGE on every UV.
    unsafe fn create_albedo(
        ctx: &VulkanContext,
        rgba: [u8; 4],
    ) -> Result<(vk::Image, vk::DeviceMemory, vk::ImageView, vk::Sampler), String> {
        Self::create_albedo_image(ctx, &rgba.repeat(ATLAS_SIZE * ATLAS_SIZE))
    }

    /// Allocate an ATLAS_SIZE×ATLAS_SIZE linear RGBA8 image, fill it with
    /// `pixels`, transition to `SHADER_READ_ONLY_OPTIMAL`, and create the
    /// view + sampler. `pixels` must be exactly ATLAS_SIZE×ATLAS_SIZE×4
    /// bytes (checked in [`Self::write_linear_image`]).
    unsafe fn create_albedo_image(
        ctx: &VulkanContext,
        pixels: &[u8],
    ) -> Result<(vk::Image, vk::DeviceMemory, vk::ImageView, vk::Sampler), String> {
        let device = &ctx.device;
        let img_info = vk::ImageCreateInfo::default()
            .image_type(vk::ImageType::TYPE_2D)
            .format(vk::Format::R8G8B8A8_UNORM)
            .extent(vk::Extent3D { width: ATLAS_SIZE as u32, height: ATLAS_SIZE as u32, depth: 1 })
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
        // Write the texels through the mapped linear image, row by row:
        // LINEAR tiling may pad rows (row_pitch > width*4).
        Self::write_linear_image(device, image, memory, pixels)?;
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

    /// Copy `pixels` (exactly ATLAS_SIZE×ATLAS_SIZE×4 bytes) into a mapped
    /// LINEAR-tiled image, one row at a time honoring the driver's row pitch.
    /// LINEAR tiling does NOT guarantee tightly packed rows — `row_pitch`
    /// may exceed `width * 4` — so a single flat memcpy would shear the
    /// atlas on drivers that pad. The caller must hold the mapping contract
    /// (memory is HOST_VISIBLE | HOST_COHERENT).
    unsafe fn write_linear_image(
        device: &ash::Device,
        image: vk::Image,
        memory: vk::DeviceMemory,
        pixels: &[u8],
    ) -> Result<(), String> {
        const EXPECTED: usize = ATLAS_SIZE * ATLAS_SIZE * 4;
        if pixels.len() != EXPECTED {
            return Err(format!(
                "albedo: expected {EXPECTED} bytes, got {}",
                pixels.len()
            ));
        }
        let layout = device.get_image_subresource_layout(
            image,
            vk::ImageSubresource {
                aspect_mask: vk::ImageAspectFlags::COLOR,
                mip_level: 0,
                array_layer: 0,
            },
        );
        let row_pitch = layout.row_pitch as usize;
        let row_bytes = ATLAS_SIZE * 4;
        if row_pitch < row_bytes {
            return Err(format!("albedo: row_pitch {row_pitch} < row {row_bytes}"));
        }
        let base = device
            .map_memory(memory, 0, layout.size, vk::MemoryMapFlags::empty())
            .map_err(|e| format!("albedo map {e:?}"))? as *mut u8;
        let dst_base = unsafe { base.add(layout.offset as usize) };
        for row in 0..ATLAS_SIZE {
            let src = unsafe { pixels.as_ptr().add(row * row_bytes) };
            let dst = unsafe { dst_base.add(row * row_pitch) };
            unsafe { std::ptr::copy_nonoverlapping(src, dst, row_bytes) };
        }
        device.unmap_memory(memory);
        Ok(())
    }

    /// (Re)upload a 256×256 RGBA atlas into the existing albedo image, in
    /// place — no image/view recreation, no descriptor rebind (the sampler
    /// slot still points at the same view).
    ///
    /// The engine calls this on the render thread when traits change with
    /// the output of
    /// [`generate_atlas`](crate::atlas::generate_atlas). Until the first
    /// call, the image holds the skin-flat seed from [`Self::create_albedo`],
    /// so rendering is identical to the old 1x1 default.
    ///
    /// Synchronization: this blocks on `device_wait_idle()` before touching
    /// mapped memory, which is a host-write → fragment-shader-read barrier
    /// by brute force — no in-flight draw may still be sampling the image.
    /// A full idle is acceptable here because this path fires at most a few
    /// times per second on trait/config changes, never per frame; the
    /// per-frame path (`draw`) stays on its lightweight per-frame fence.
    /// A finer barrier (per-frame fence wait + layout-preserving
    /// HOST→FRAGMENT_SHADER image barrier) would avoid the stall but adds
    /// command-buffer churn for zero visible gain on a config-change path.
    ///
    /// # Safety
    /// Must be called on the render thread (the same thread that draws).
    pub unsafe fn upload_atlas(&mut self, pixels: &[u8]) -> Result<(), String> {
        const EXPECTED: usize = ATLAS_SIZE * ATLAS_SIZE * 4;
        if pixels.len() != EXPECTED {
            return Err(format!(
                "albedo: expected {EXPECTED} bytes, got {}",
                pixels.len()
            ));
        }
        let device = &self.ctx.device;
        device.device_wait_idle().map_err(|e| format!("albedo idle {e:?}"))?;
        unsafe { Self::write_linear_image(device, self.albedo_image, self.albedo_memory, pixels) }
    }
}
