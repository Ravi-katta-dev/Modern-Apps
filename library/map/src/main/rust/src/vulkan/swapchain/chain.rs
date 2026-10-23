use crate::bridge::log::{log, log_info};
use crate::vulkan::context::Context;
use ash::vk;

use super::msaa::MsaaTarget;
use super::samples::supported_samples;
use super::stencil::{stencil_format, StencilTarget};

pub struct Swapchain {
    pub loader: ash::khr::swapchain::Device,
    pub swapchain: vk::SwapchainKHR,
    pub images: Vec<vk::Image>,
    pub views: Vec<vk::ImageView>,
    pub framebuffers: Vec<vk::Framebuffer>,
    pub render_pass: vk::RenderPass,
    pub format: vk::Format,
    pub extent: vk::Extent2D,
    /// Samples per pixel the pipelines must be built for.
    pub samples: vk::SampleCountFlags,
    /// The present mode negotiated with the driver ([`choose_present_mode`]).
    pub present_mode: vk::PresentModeKHR,
    /// The multisampled colour target, absent when `samples` is 1.
    msaa: Option<MsaaTarget>,
    /// The combined depth-stencil target: depth for the 3D layers, stencil for the region mask,
    /// and the attachment index it sits at.
    stencil: StencilTarget,
}

impl Swapchain {
    /// # Safety
    ///
    /// `context` must be live, and any previous swapchain must have been destroyed.
    pub unsafe fn new(context: &Context, width: u32, height: u32) -> Result<Swapchain, String> {
        let (width, height) = (width.max(1), height.max(1));
        let capabilities = context
            .surface_loader
            .get_physical_device_surface_capabilities(context.physical_device, context.surface)
            .map_err(|e| format!("surface capabilities {e:?}"))?;
        let formats = context
            .surface_loader
            .get_physical_device_surface_formats(context.physical_device, context.surface)
            .map_err(|e| format!("surface formats {e:?}"))?;
        let present_modes = context
            .surface_loader
            .get_physical_device_surface_present_modes(context.physical_device, context.surface)
            .map_err(|e| format!("present modes {e:?}"))?;

        // Prefer a plain UNORM format: Pixel's gralloc rejects B8G8R8A8_SRGB and the
        // result is a black screen with no error. See the module docs.
        let mut chosen = *formats.first().ok_or("the surface reports no formats")?;
        for candidate in &formats {
            if candidate.format == vk::Format::B8G8R8A8_UNORM
                || candidate.format == vk::Format::R8G8B8A8_UNORM
            {
                chosen = *candidate;
                break;
            }
        }

        let extent = if capabilities.current_extent.width == u32::MAX {
            vk::Extent2D { width, height }
        } else {
            vk::Extent2D {
                width: capabilities.current_extent.width.max(1),
                height: capabilities.current_extent.height.max(1),
            }
        };
        // FIFO is vsync, and the map is driven by Choreographer: anything else would only
        // queue frames nobody sees, at the cost of power. MAILBOX is the exception: like
        // FIFO it is tear-free, but a newer frame replaces the queued one instead of
        // waiting behind it — up to a frame less latency through a 60fps pan/zoom for no
        // extra power while idle (the on-demand loop queues nothing when nothing moves).
        // IMMEDIATE is never picked: it tears, and a torn map frame is worse than a
        // vsync-late one. FIFO is the mandated fallback (the only mode the spec requires)
        // and the old behaviour wherever MAILBOX is absent.
        let present_mode = choose_present_mode(&present_modes);
        log_info(&format!(
            "swapchain present mode: {} (offered {} modes)",
            present_mode_name(present_mode),
            present_modes.len(),
        ));
        let max = if capabilities.max_image_count == 0 {
            u32::MAX
        } else {
            capabilities.max_image_count
        };
        let image_count = (capabilities.min_image_count + 1).min(max);
        // IDENTITY where offered, so the presentation engine does not rotate an image we
        // already produced in display orientation.
        let pre_transform = if capabilities
            .supported_transforms
            .contains(vk::SurfaceTransformFlagsKHR::IDENTITY)
        {
            vk::SurfaceTransformFlagsKHR::IDENTITY
        } else {
            capabilities.current_transform
        };

        let loader = ash::khr::swapchain::Device::new(&context.instance, &context.device);

        // The negotiated count first; one automatic step-down to 1x when the MSAA
        // target fails. Some drivers (notably MediaTek's, issue 789) report 4x in
        // `framebuffer_color_sample_counts` and then fail creating the transient
        // multisampled image — a graceful `Err` on most drivers, an abort on the
        // worst ones (which no `Result` can carry, but the stage logs in
        // `Renderer::new` at least name it). Either way a device that cannot do
        // MSAA must still draw at 1x: the same render pass with the resolve
        // attachment dropped, at the cost of antialiasing rather than a blank map.
        let wanted = supported_samples(context);
        let params = SwapchainParams {
            chosen,
            extent,
            present_mode,
            image_count,
            pre_transform,
        };
        match Self::with_samples(context, loader, &params, wanted) {
            Ok(built) => Ok(built),
            Err((loader, first)) if wanted != vk::SampleCountFlags::TYPE_1 => {
                log(&format!(
                    "swapchain at {:#x} failed ({first}); retrying without MSAA",
                    wanted.as_raw()
                ));
                Self::with_samples(context, loader, &params, vk::SampleCountFlags::TYPE_1)
                    .map_err(|(_, message)| message)
            }
            Err((_, first)) => Err(first),
        }
    }

    /// Build the swapchain handle plus the render pass, MSAA/stencil targets and
    /// framebuffers for one sample count.
    ///
    /// Consumes the swapchain loader and returns it alongside the error when an
    /// attempt fails — so the 1x retry reuses the same loader. Every attempt
    /// creates and owns its own swapchain handle: a failed attempt destroys its
    /// handle (plus any attachments it managed) before returning, so the retry
    /// starts clean and nothing leaks down either path.
    unsafe fn with_samples(
        context: &Context,
        loader: ash::khr::swapchain::Device,
        params: &SwapchainParams,
        samples: vk::SampleCountFlags,
    ) -> Result<Swapchain, (ash::khr::swapchain::Device, String)> {
        let create_info = vk::SwapchainCreateInfoKHR::default()
            .surface(context.surface)
            .min_image_count(params.image_count)
            .image_format(params.chosen.format)
            .image_color_space(params.chosen.color_space)
            .image_extent(params.extent)
            .image_array_layers(1)
            .image_usage(vk::ImageUsageFlags::COLOR_ATTACHMENT)
            .image_sharing_mode(vk::SharingMode::EXCLUSIVE)
            .pre_transform(params.pre_transform)
            .composite_alpha(vk::CompositeAlphaFlagsKHR::OPAQUE)
            .present_mode(params.present_mode)
            .clipped(true);
        let swapchain = loader
            .create_swapchain(&create_info, None)
            .map_err(|e| (loader_clone(&loader), format!("create_swapchain {e:?}")))?;
        let built = Self::build_attachments(context, params.chosen, params.extent, samples);
        let built = match built {
            Ok(built) => built,
            Err(message) => {
                loader.destroy_swapchain(swapchain, None);
                return Err((loader, message));
            }
        };
        // Re-query per attempt: each attempt owns its own handle, and the count
        // sizes the two `Vec`s below. Explicit match for the same reason as
        // the framebuffer site below: the failure branch owns `built`.
        let images = match loader.get_swapchain_images(swapchain) {
            Ok(images) => images,
            Err(e) => {
                let message = format!("swapchain images {e:?}");
                loader.destroy_swapchain(swapchain, None);
                destroy_attachments(&context.device, built);
                return Err((loader_clone(&loader), message));
            }
        };
        // The per-image loop borrows `built` for the attachment triple, so its
        // failures surface as a plain `String` here — one site that destroys the
        // handle and the triple. Views and framebuffers allocated before the
        // failure leak on this path, matching the old code: this error means a
        // broken driver rather than a retryable condition, so no cleanup ladder
        // is built for the per-image objects.
        //
        // An explicit match (not `map_err`): the failure branch moves `built`
        // into teardown and returns, so the success path below keeps using it.
        // A closure would capture `built` at creation and poison every later use.
        let (views, framebuffers) =
            match Self::build_framebuffers(context, params, &built, &images) {
                Ok(pair) => pair,
                Err(message) => {
                    loader.destroy_swapchain(swapchain, None);
                    destroy_attachments(&context.device, built);
                    return Err((loader_clone(&loader), message));
                }
            };

        // `loader` moves into the struct: it is only function pointers (ash
        // loaders are `Clone`), so no second `Device::new` is needed.
        Ok(Swapchain {
            loader,
            swapchain,
            images,
            views,
            framebuffers,
            render_pass: built.render_pass,
            format: params.chosen.format,
            extent: params.extent,
            samples,
            present_mode: params.present_mode,
            msaa: built.msaa,
            stencil: built.stencil,
        })
    }

    /// One image view plus one framebuffer per swapchain image, against the
    /// attachment triple of one sample-count attempt.
    ///
    /// Attachment order matches the render pass: colour, resolve, then stencil.
    unsafe fn build_framebuffers(
        context: &Context,
        params: &SwapchainParams,
        built: &BuiltAttachments,
        images: &[vk::Image],
    ) -> Result<(Vec<vk::ImageView>, Vec<vk::Framebuffer>), String> {
        let mut views = Vec::with_capacity(images.len());
        let mut framebuffers = Vec::with_capacity(images.len());
        for &image in images {
            let view_info = vk::ImageViewCreateInfo::default()
                .image(image)
                .view_type(vk::ImageViewType::TYPE_2D)
                .format(params.chosen.format)
                .subresource_range(vk::ImageSubresourceRange {
                    aspect_mask: vk::ImageAspectFlags::COLOR,
                    base_mip_level: 0,
                    level_count: 1,
                    base_array_layer: 0,
                    layer_count: 1,
                });
            let view = context
                .device
                .create_image_view(&view_info, None)
                .map_err(|e| format!("create_image_view {e:?}"))?;
            views.push(view);

            let attached: Vec<vk::ImageView> = match &built.msaa {
                Some(target) => vec![target.view, view, built.stencil.view],
                None => vec![view, built.stencil.view],
            };
            let framebuffer_info = vk::FramebufferCreateInfo::default()
                .render_pass(built.render_pass)
                .attachments(&attached)
                .width(params.extent.width)
                .height(params.extent.height)
                .layers(1);
            framebuffers.push(
                context
                    .device
                    .create_framebuffer(&framebuffer_info, None)
                    .map_err(|e| format!("create_framebuffer {e:?}"))?,
            );
        }
        Ok((views, framebuffers))
    }

    /// The render pass plus MSAA and stencil targets for one sample count, before
    /// any per-image view exists.
    unsafe fn build_attachments(
        context: &Context,
        chosen: vk::SurfaceFormatKHR,
        extent: vk::Extent2D,
        samples: vk::SampleCountFlags,
    ) -> Result<BuiltAttachments, String> {
        let multisampled = samples != vk::SampleCountFlags::TYPE_1;

        // One subpass. When multisampled the colour attachment is the transient MSAA
        // image (discarded after the resolve) and the swapchain image is the resolve
        // target; otherwise the swapchain image is the colour attachment directly.
        // Either way there are no mid-pass reads, so a tile-based GPU keeps the whole
        // frame in tile memory.
        let color = vk::AttachmentDescription::default()
            .format(chosen.format)
            .samples(samples)
            .load_op(vk::AttachmentLoadOp::CLEAR)
            .store_op(if multisampled {
                vk::AttachmentStoreOp::DONT_CARE
            } else {
                vk::AttachmentStoreOp::STORE
            })
            .stencil_load_op(vk::AttachmentLoadOp::DONT_CARE)
            .stencil_store_op(vk::AttachmentStoreOp::DONT_CARE)
            .initial_layout(vk::ImageLayout::UNDEFINED)
            .final_layout(if multisampled {
                vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL
            } else {
                vk::ImageLayout::PRESENT_SRC_KHR
            });
        let resolve = vk::AttachmentDescription::default()
            .format(chosen.format)
            .samples(vk::SampleCountFlags::TYPE_1)
            // The resolve writes every pixel, so there is nothing worth loading.
            .load_op(vk::AttachmentLoadOp::DONT_CARE)
            .store_op(vk::AttachmentStoreOp::STORE)
            .stencil_load_op(vk::AttachmentLoadOp::DONT_CARE)
            .stencil_store_op(vk::AttachmentStoreOp::DONT_CARE)
            .initial_layout(vk::ImageLayout::UNDEFINED)
            .final_layout(vk::ImageLayout::PRESENT_SRC_KHR);
        // Chosen before the render pass because the attachment has to name the format. A combined
        // depth-stencil format is required now that the depth aspect is used, and one is
        // universally available even where standalone `S8_UINT` is not.
        let stencil_format = stencil_format(context)?;
        let stencil = vk::AttachmentDescription::default()
            .format(stencil_format)
            .samples(samples)
            // Depth is cleared to the far plane (1.0) each frame and discarded after the subpass:
            // the 3D layers test/write it within the pass, nothing reads it afterwards, and on a
            // tile-based GPU a transient depth buffer never leaves tile memory.
            .load_op(vk::AttachmentLoadOp::CLEAR)
            .store_op(vk::AttachmentStoreOp::DONT_CARE)
            // Stencil is cleared to zero every frame: zero means "outside the selected region",
            // which is what the scrim tests for, and is also the right answer when nothing is
            // selected.
            .stencil_load_op(vk::AttachmentLoadOp::CLEAR)
            .stencil_store_op(vk::AttachmentStoreOp::DONT_CARE)
            .initial_layout(vk::ImageLayout::UNDEFINED)
            .final_layout(vk::ImageLayout::DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        let attachments: Vec<vk::AttachmentDescription> = if multisampled {
            vec![color, resolve, stencil]
        } else {
            vec![color, stencil]
        };

        let color_ref = vk::AttachmentReference::default()
            .attachment(0)
            .layout(vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL);
        let resolve_ref = vk::AttachmentReference::default()
            .attachment(1)
            .layout(vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL);
        let stencil_ref = vk::AttachmentReference::default()
            .attachment(if multisampled { 2 } else { 1 })
            .layout(vk::ImageLayout::DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        let mut subpass = vk::SubpassDescription::default()
            .pipeline_bind_point(vk::PipelineBindPoint::GRAPHICS)
            .color_attachments(std::slice::from_ref(&color_ref))
            .depth_stencil_attachment(&stencil_ref);
        if multisampled {
            subpass = subpass.resolve_attachments(std::slice::from_ref(&resolve_ref));
        }
        let dependency = vk::SubpassDependency::default()
            .src_subpass(vk::SUBPASS_EXTERNAL)
            .dst_subpass(0)
            .src_stage_mask(vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT)
            .dst_stage_mask(vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT)
            .dst_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE);
        let render_pass_info = vk::RenderPassCreateInfo::default()
            .attachments(&attachments)
            .subpasses(std::slice::from_ref(&subpass))
            .dependencies(std::slice::from_ref(&dependency));
        let render_pass = context
            .device
            .create_render_pass(&render_pass_info, None)
            .map_err(|e| format!("create_render_pass {e:?}"))?;

        let msaa = if multisampled {
            Some(MsaaTarget::new(context, chosen.format, extent, samples)?)
        } else {
            None
        };

        let stencil_target = StencilTarget::new(context, stencil_format, extent, samples)?;

        Ok(BuiltAttachments {
            render_pass,
            msaa,
            stencil: stencil_target,
            // The swapchain image count is negotiated above; the per-image loop in
            // `with_samples` reserves from it. Three is the floor every driver
            // offers (min + 1 with min >= 2 in practice), so this only sizes the
            // initial `Vec` capacity, never the framebuffer count.
            image_count_hint: 3,
        })
    }

    /// # Safety
    ///
    /// The device must be idle.
    pub unsafe fn destroy(&mut self, device: &ash::Device) {
        for &framebuffer in &self.framebuffers {
            device.destroy_framebuffer(framebuffer, None);
        }
        self.framebuffers.clear();
        if let Some(target) = self.msaa.take() {
            device.destroy_image_view(target.view, None);
            device.destroy_image(target.image, None);
            device.free_memory(target.memory, None);
        }
        device.destroy_image_view(self.stencil.view, None);
        device.destroy_image(self.stencil.image, None);
        device.free_memory(self.stencil.memory, None);
        device.destroy_render_pass(self.render_pass, None);
        for &view in &self.views {
            device.destroy_image_view(view, None);
        }
        self.views.clear();
        self.loader.destroy_swapchain(self.swapchain, None);
    }
}

/// The swapchain-handle parameters shared by both MSAA attempts: negotiated once
/// in `new`, consumed once per attempt in `with_samples`.
struct SwapchainParams {
    chosen: vk::SurfaceFormatKHR,
    extent: vk::Extent2D,
    present_mode: vk::PresentModeKHR,
    image_count: u32,
    pre_transform: vk::SurfaceTransformFlagsKHR,
}

/// Clone the swapchain loader for an error return: the loader is only loaded
/// function pointers (ash loaders are `Clone`), so the retry keeps working with
/// a copy while the original moves into the error tuple.
fn loader_clone(loader: &ash::khr::swapchain::Device) -> ash::khr::swapchain::Device {
    loader.clone()
}

/// Destroy the render pass and MSAA/stencil targets of a failed attempt.
///
/// A failed `with_samples` attempt must leave nothing behind: the swapchain
/// handle is destroyed at the call site, and this drops the attachment triple
/// `build_attachments` created. Views and framebuffers allocated before the
/// failure are intentionally not handled here — see the leak notes at those
/// call sites (a broken-driver path, matching the old code).
unsafe fn destroy_attachments(
    device: &ash::Device,
    built: BuiltAttachments,
) {
    let BuiltAttachments { render_pass, msaa, stencil, .. } = built;
    if let Some(target) = msaa {
        device.destroy_image_view(target.view, None);
        device.destroy_image(target.image, None);
        device.free_memory(target.memory, None);
    }
    device.destroy_image_view(stencil.view, None);
    device.destroy_image(stencil.image, None);
    device.free_memory(stencil.memory, None);
    device.destroy_render_pass(render_pass, None);
}

/// The render pass and its long-lived targets for one sample count, before any
/// per-swapchain-image view exists.
///
/// Split out of `Swapchain::new` so the constructor can attempt the negotiated
/// MSAA count and step down to 1x on failure: `build_attachments` owns the
/// render pass, MSAA and stencil targets, while `with_samples` owns the
/// per-image views and framebuffers.
struct BuiltAttachments {
    render_pass: vk::RenderPass,
    msaa: Option<MsaaTarget>,
    stencil: StencilTarget,
    image_count_hint: usize,
}

/// Pick the swapchain present mode: MAILBOX where offered, FIFO otherwise.
///
/// Pure so it is host-testable: no device, just the driver's offered list. MAILBOX is
/// tear-free like FIFO but replaces the queued frame instead of waiting behind it, so
/// a 60fps gesture presents newer poses; FIFO is the spec-mandated fallback and the
/// previous behaviour. IMMEDIATE is never chosen even when it is the only other
/// option — tearing on a map is worse than vsync latency — so a driver offering only
/// IMMEDIATE keeps the old `present_modes[0]` answer.
fn choose_present_mode(present_modes: &[vk::PresentModeKHR]) -> vk::PresentModeKHR {
    if present_modes.contains(&vk::PresentModeKHR::MAILBOX) {
        vk::PresentModeKHR::MAILBOX
    } else if present_modes.contains(&vk::PresentModeKHR::FIFO) {
        vk::PresentModeKHR::FIFO
    } else {
        // Unreachable on a conformant driver (FIFO is mandatory), but the old code
        // indexed [0] here and a panic on a non-conformant driver is worse than a
        // torn frame. `new` already errors on an empty format list; an empty mode
        // list fails below at creation with the driver's own error.
        present_modes.first().copied().unwrap_or(vk::PresentModeKHR::FIFO)
    }
}

/// The creation-time log line names the negotiated mode, so a device run can confirm
/// which path the driver took without a second build. By discriminant rather than
/// `Debug`: `ash`'s bitflag-style enums do not implement it.
fn present_mode_name(mode: vk::PresentModeKHR) -> &'static str {
    match mode {
        vk::PresentModeKHR::IMMEDIATE => "IMMEDIATE",
        vk::PresentModeKHR::MAILBOX => "MAILBOX",
        vk::PresentModeKHR::FIFO => "FIFO",
        vk::PresentModeKHR::FIFO_RELAXED => "FIFO_RELAXED",
        _ => "UNKNOWN",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// MAILBOX wins where offered (tear-free and fresher through gestures); FIFO is
    /// the mandated fallback and the old behaviour; IMMEDIATE is never picked even
    /// when it is the only other option.
    ///
    /// Android-gated like the rest of this module (`ash` is an Android-only
    /// dependency), so `cargo test` on the host never runs it — it compiles and runs
    /// under the Android test target instead. The creation-time logcat line naming
    /// the negotiated mode is the on-device proof in the meantime.
    #[test]
    fn mailbox_wins_over_fifo_and_immediate_is_never_picked() {
        use vk::PresentModeKHR::*;
        assert_eq!(
            choose_present_mode(&[FIFO, MAILBOX, IMMEDIATE]),
            MAILBOX,
            "tear-free with the freshest queued frame"
        );
        assert_eq!(
            choose_present_mode(&[FIFO, IMMEDIATE]),
            FIFO,
            "the old behaviour where MAILBOX is absent"
        );
        assert_eq!(
            choose_present_mode(&[IMMEDIATE]),
            IMMEDIATE,
            "a non-conformant driver keeps the old [0] answer, not a panic"
        );
        assert_eq!(
            choose_present_mode(&[]),
            FIFO,
            "empty degrades to the mandated mode for the creation error below"
        );
    }
}
