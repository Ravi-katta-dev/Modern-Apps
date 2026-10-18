//! The swapchain, its render pass and framebuffers.
//!
//! # Depth: transient, and shared with the stencil
//!
//! The flat 2D basemap has nothing to occlude — correctness there comes from **draw order**
//! (layer-major across tiles) and alpha blending, so those layers run with depth test and
//! write off and are unaffected by the attachment below. Tilt and the 3D layers (buildings,
//! terrain) change that: extruded geometry must occlude itself under a perspective camera, so
//! the pass now carries a depth buffer that the depth-enabled pipeline variant tests against.
//!
//! It costs almost nothing on the target hardware. The attachment is the **same combined
//! depth-stencil image** the region mask already needed for its stencil, so depth adds no new
//! image — only a `CLEAR`/`DONT_CARE` on the depth aspect. Like the MSAA and stencil targets
//! it is `TRANSIENT_ATTACHMENT` + `LAZILY_ALLOCATED`: on a tile-based mobile GPU the depth
//! samples live and die inside tile memory and never reach main memory, so there is no real
//! backing store and no store-out bandwidth (`store_op` is `DONT_CARE`).
//!
//! # Multisampling
//!
//! Rendering was single-sampled, and with no coverage term in any shader that made every
//! polygon edge a staircase — coastlines, park boundaries and building corners all
//! stepped a pixel at a time, which is most of what read as the renderer being lower
//! resolution than MapLibre. The colour attachment is now multisampled and resolved into
//! the swapchain image at the end of the subpass.
//!
//! The bandwidth argument above still holds, which is why the multisampled image is
//! `TRANSIENT_ATTACHMENT` + `LAZILY_ALLOCATED` where the driver offers it: on a
//! tile-based GPU the samples live and die inside tile memory and never reach main
//! memory, so the resolve is close to free and the image needs no real backing store.
//! `store_op` is `DONT_CARE` for the same reason — only the resolve target is kept.
//!
//! # Present mode: MAILBOX where offered, FIFO otherwise
//!
//! The swapchain used to pin FIFO. MAILBOX is tear-free like FIFO but lets a newer
//! frame replace the queued one, so a 60fps gesture presents poses up to a frame
//! fresher; the on-demand loop queues nothing while idle, so there is no power cost
//! when the map is still. IMMEDIATE is never picked (tearing), FIFO is the
//! spec-mandated fallback — see [`chain`](self::chain) for the selection and the
//! creation-time logcat line naming the negotiated mode.
//!
//! # Two hard-won details carried over from `games/voxels`
//!
//! * **`B8G8R8A8_UNORM` in preference to any `SRGB` format.** `voxels/swapchain.rs:39`
//!   records that Pixel's gralloc rejects the SRGB format and the result is a black
//!   screen.
//! * **A classic `VkRenderPass`, not `KHR_dynamic_rendering`.**
//!   `voxels/swapchain.rs:74` records the crash — "Unable to load cmd_begin_rendering" —
//!   from assuming the extension is present.
//!
//! # P5.3 decision: still a render pass (dynamic rendering probed, not adopted)
//!
//! The context creation log names the driver's API version and whether
//! `VK_KHR_dynamic_rendering` exists on it — the "where drivers allow" half is
//! measured, not assumed. Adoption would need *two* record paths (every `record_*`
//! pass, the pipeline create-info, the rebuild path and the pick pass all name the
//! render pass), validated per device, for a win this renderer cannot collect: the
//! per-frame state that changes is push constants, which dynamic rendering does not
//! retain either. Secondary command buffers fail the same way — their win is threaded
//! recording, which the all-Vulkan-on-main rule forbids, and single-threaded they are
//! allocation + indirection over one primary record. So the probe stays a log line,
//! and the ordering contracts it would have had to preserve (mask/stencil groups
//! order-pinned, separate `*_globe` groups, ScratchRing ranges fenced per frame —
//! see `Renderer::render`'s reset-after-fence) stay exactly as they are.

mod chain;
mod msaa;
mod samples;
mod stencil;

pub use chain::Swapchain;
pub use samples::WANTED_SAMPLES;
