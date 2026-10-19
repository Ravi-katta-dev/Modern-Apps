# AGENTS.md — games/hub/src/main/rust/ (`yuu_avatar`)

> Scoped supplement. Hub-local 3D avatar engine: glTF upper-body base mesh,
> skeletal skinning, preset poses, Vulkan-first + GLES fallback renderer.

## Overview
**Purpose:** Real-time 3D Yuu avatars for hero surfaces (editor preview,
profile/dashboard headers). Traits arrive as `YuuFaceConfig` JSON, poses as
preset ids; the engine maps both onto bone matrices every frame.
**Key features:** 13-joint upper-body rig, 5 preset poses, procedural fallback
mesh when no glb is bundled, turntable preview camera with idle sway.
**Architecture:** `avatar.rs` (skeleton/traits/poses/FK) → `engine.rs`
(lifecycle/camera) → `renderer.rs` (Vulkan/GLES dispatch) → `vulkan/` + `gles/`
backends; `mesh.rs` (procedural fallback + glTF loader); `lib.rs` (JNI).
**Entry points:** `lib.rs` JNI fns → `engine::set_config/tick_and_render`.

## Skeleton (UBO order — do not reorder)
`root → spine → chest → neck → head`, plus per side
`clavicle → upperArm → forearm → hand` (`avatar.rs: Joint`, `ALL_JOINTS`).
`MAX_BONES = 32` in the shader UBO; joints beyond index 12 are identity.

## Trait mapping (coarse, v1)
`skinTone`→material, `bodyHeight/Weight`→bone scale, `faceShape`→head scale,
style fields→attachment/texture variants (later phase);
`mole/wrinkle/blush` explicitly skipped in v1.

## Gotchas
- Vulkan `new()` currently reports unavailable until the swapchain bring-up
  lands; GLES path is complete. Kotlin `isAvailable` fallback keeps 2D.
- `gltf` parses the first mesh primitive with JOINTS_0/WEIGHTS_0; node names
  must match `joint_name()` or they remap to root.
- Workspace lints deny `unwrap_used`/`panic`/`expect_used` — fall back, never
  crash (missing glb → procedural mesh).
- Shaders: `*.vert`/`*.frag` (Vulkan, Y-flip) + `*_gles.*` twins (no flip);
  `build.rs` compiles via NDK glslc with minimal-SPV fallback.
