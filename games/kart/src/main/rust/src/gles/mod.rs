//! GLES 3.0 fallback renderer for Vulkan-less devices (ONN 4K box, Mali-G31).
//!
//! Same layering as [`crate::vulkan`]: `egl` owns the display/surface/context,
//! `shaders` compiles the GLSL ES program, `buffers` uploads the
//! [`crate::mesh`] builders verbatim, `renderer` draws + presents. Runtime
//! selection lives in [`crate::engine`]: try Vulkan, fall back to GLES, fail
//! only if both fail.

pub mod buffers;
pub mod egl;
pub mod renderer;
pub mod shaders;
