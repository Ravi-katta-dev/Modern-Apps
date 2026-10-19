//! Avatar engine: lifecycle, preview camera, per-frame bone upload.
//!
//! Owns the single hero-surface renderer, the current traits + pose, and the
//! turntable clock. The render thread ticks here; [`tick_and_render`] uploads
//! the bone-matrix UBO and draws the avatar mesh every frame.

use crate::avatar::{compute_bone_matrices, preset_by_id, preset_poses, YuuPose, YuuTraits, MAX_BONES};
use crate::mesh::{build_avatar_mesh, load_gltf_mesh, YuuVertex};
use crate::renderer::Renderer;
use glam::Mat4;
use std::sync::{Mutex, OnceLock};
use std::time::Instant;

/// Engine state behind the global lock.
pub struct Engine {
    /// Active GPU backend, if the surface is live.
    pub renderer: Option<Renderer>,
    /// Current trait mapping (from `YuuFaceConfig` JSON).
    pub traits: YuuTraits,
    /// Current pose preset.
    pub pose: YuuPose,
    /// Turntable clock start.
    pub started: Instant,
    /// Static avatar mesh (uploaded once per surface, recapped per backend).
    pub mesh: Vec<YuuVertex>,
    /// Vertex count from the last upload.
    pub mesh_count: usize,
    /// Surface width for the camera aspect.
    pub width: u32,
    /// Surface height for the camera aspect.
    pub height: u32,
    /// Paused (surface alive but hidden): skip draws, keep state.
    pub paused: bool,
    /// Bundled glb bytes, when the APK ships `base_upperbody.glb`.
    pub bundled_mesh: Option<Vec<u8>>,
    /// Render-thread generation: bumped on every surface creation so a stale
    /// thread (rotation) exits instead of racing the new one.
    pub generation: u64,
}

impl Engine {
    /// Fresh engine with the procedural fallback mesh and idle pose.
    pub fn new() -> Self {
        Self {
            renderer: None,
            traits: YuuTraits::default(),
            pose: preset_poses().into_iter().next().unwrap_or(YuuPose {
                id: "idle".into(),
                name: "Idle".into(),
                joints: Default::default(),
            }),
            started: Instant::now(),
            mesh: build_avatar_mesh(),
            mesh_count: 0,
            width: 512,
            height: 512,
            paused: false,
            bundled_mesh: None,
            generation: 0,
        }
    }
}

impl Default for Engine {
    fn default() -> Self {
        Self::new()
    }
}

// SAFETY: guarded by the mutex; the renderer (Vulkan/GLES handles) is only
// touched on the render thread, same arrangement as games/kart.
unsafe impl Send for Engine {}

static ENGINE: OnceLock<Mutex<Engine>> = OnceLock::new();

fn engine() -> &'static Mutex<Engine> {
    ENGINE.get_or_init(|| Mutex::new(Engine::new()))
}

/// Adopt the engine state (idempotent).
pub fn ensure_engine() {
    let _ = engine();
}

/// Replace the current traits from `YuuFaceConfig` JSON.
pub fn set_traits_json(json: &str) {
    if let Ok(mut eng) = engine().lock() {
        eng.traits = YuuTraits::from_config_json(json);
    }
}

/// Replace the current pose by preset id (unknown ids keep the old pose).
pub fn set_pose_id(pose_id: &str) {
    if let Some(preset) = preset_by_id(pose_id) {
        if let Ok(mut eng) = engine().lock() {
            eng.pose = preset;
        }
    }
}

/// Set both traits and pose from JSON in one call (surface creation path).
pub fn set_config(config_json: &str, pose_json: &str) {
    set_traits_json(config_json);
    // Pose arrives either as a bare id string or `{"id": ...}`.
    let id = pose_json.trim().trim_matches('"').to_string();
    let id = serde_json::from_str::<serde_json::Value>(pose_json)
        .ok()
        .and_then(|v| v.get("id").and_then(|s| s.as_str()).map(str::to_string))
        .unwrap_or(id);
    if !id.is_empty() {
        set_pose_id(&id);
    }
}

/// Provide bundled glb bytes (from APK assets via JNI) for the next surface.
pub fn set_bundled_mesh(bytes: Vec<u8>) {
    if let Ok(mut eng) = engine().lock() {
        eng.bundled_mesh = Some(bytes);
    }
}

/// Pause or resume drawing (keeps the renderer alive).
pub fn set_paused(paused: bool) {
    if let Ok(mut eng) = engine().lock() {
        eng.paused = paused;
    }
}

/// Whether a renderer is currently live.
pub fn has_renderer() -> bool {
    engine().lock().map(|e| e.renderer.is_some()).unwrap_or(false)
}

/// Whether the render thread should keep running.
pub fn is_running() -> bool {
    has_renderer()
}

/// Log helper for backend selection.
pub fn log_backend(name: &str) {
    android_log(name);
}

#[cfg(target_os = "android")]
fn android_log(msg: &str) {
    // Best-effort logcat line; failures are ignored (no UI impact).
    let tag = std::ffi::CString::new("yuu_avatar").unwrap_or_default();
    let text = std::ffi::CString::new(format!("backend: {msg}")).unwrap_or_default();
    unsafe {
        ndk_log(msg, &tag, &text);
    }
}

#[cfg(target_os = "android")]
unsafe fn ndk_log(_msg: &str, tag: &std::ffi::CString, text: &std::ffi::CString) {
    unsafe {
        // __android_log_write(ANDROID_LOG_INFO=4, tag, text).
        extern "C" {
            fn __android_log_write(prio: i32, tag: *const i8, text: *const i8) -> i32;
        }
        __android_log_write(4, tag.as_ptr() as *const i8, text.as_ptr() as *const i8);
    }
}

#[cfg(not(target_os = "android"))]
fn android_log(_msg: &str) {}

/// Preview camera: fixed-radius turntable looking at the upper body.
///
/// Mirrors kart `view_proj_for` (look_at_rh + perspective_rh via glam) with a
/// slow orbit so heroes show depth; the idle sway lives in the bone pass.
pub fn view_proj_for(width: u32, height: u32, time_s: f32) -> ([[f32; 4]; 4], [f32; 3]) {
    let aspect = width.max(1) as f32 / height.max(1) as f32;
    let angle = time_s * 0.25;
    let radius = 2.6;
    let eye = glam::Vec3::new(angle.sin() * radius, 1.15, angle.cos() * radius);
    let center = glam::Vec3::new(0.0, 0.85, 0.0);
    let up = glam::Vec3::Y;
    let view = Mat4::look_at_rh(eye, center, up);
    let proj = Mat4::perspective_rh_gl(38.0f32.to_radians(), aspect, 0.1, 20.0);
    let view_proj = proj * view;
    (view_proj.to_cols_array_2d(), eye.to_array())
}

/// Create the renderer for `window` and upload the avatar mesh.
///
/// # Safety
/// `window` must be a live adopted `ANativeWindow`; ownership stays with the
/// engine (released on destroy), as on the kart path.
pub unsafe fn create_renderer(window: *mut crate::vulkan::context::ANativeWindow, width: i32, height: i32) -> bool {
    // Parse outside the lock: glTF parsing allocates and must not stall the
    // 60 Hz tick thread waiting on the engine mutex.
    let bundled: Option<Vec<u8>> = engine().lock().ok().and_then(|e| e.bundled_mesh.clone());
    let parsed: Option<Vec<YuuVertex>> = bundled.as_deref().map(load_gltf_mesh);
    let (mesh, w, h) = {
        let mut eng = match engine().lock() {
            Ok(e) => e,
            Err(_) => return false,
        };
        // Prefer the bundled glb when present, else the procedural fallback.
        if let Some(m) = parsed {
            eng.mesh = m;
        }
        eng.width = width.max(1) as u32;
        eng.height = height.max(1) as u32;
        (eng.mesh.clone(), eng.width, eng.height)
    };
    let renderer = match unsafe { Renderer::select(window, width, height, w, h) } {
        Ok(r) => r,
        Err(_) => return false,
    };
    if let Ok(mut eng) = engine().lock() {
        eng.renderer = Some(renderer);
        eng.mesh_count = mesh.len();
        eng.paused = false;
    }
    // Upload on the render thread with the backend's context current.
    upload_mesh(&mesh)
}

/// Upload the avatar mesh to the live renderer.
pub fn upload_mesh(mesh: &[YuuVertex]) -> bool {
    let mut eng = match engine().lock() {
        Ok(e) => e,
        Err(_) => return false,
    };
    if let Some(renderer) = eng.renderer.as_mut() {
        unsafe { renderer.upload_avatar(mesh) };
        eng.mesh_count = mesh.len();
        true
    } else {
        false
    }
}

/// Resize the swapchain.
pub fn resize_renderer(width: i32, height: i32) {
    if let Ok(mut eng) = engine().lock() {
        let w = width.max(1) as u32;
        let h = height.max(1) as u32;
        eng.width = w;
        eng.height = h;
        if let Some(renderer) = eng.renderer.as_mut() {
            unsafe { renderer.resize(w, h) };
        }
    }
}

/// One tick: compute bones, upload the UBO, draw.
pub fn tick_and_render() {
    let (view_proj, cam_pos, bones, skin_rgb, count, paused) = {
        let eng = match engine().lock() {
            Ok(e) => e,
            Err(_) => return,
        };
        if eng.renderer.is_none() || eng.paused {
            return;
        }
        let t = eng.started.elapsed().as_secs_f32();
        let (vp, cp) = view_proj_for(eng.width, eng.height, t);
        let bones = compute_bone_matrices(&eng.traits, &eng.pose, t);
        let skin = eng.traits.skin_rgb();
        (vp, cp, bones, skin, eng.mesh_count, eng.paused)
    };
    if paused {
        return;
    }
    // Bone matrices as nested arrays for the draw call.
    let bone_arrays: [[f32; 16]; MAX_BONES] = bones.map(|m| m.to_cols_array());
    if let Ok(mut eng) = engine().lock() {
        if let Some(renderer) = eng.renderer.as_mut() {
            let _ = unsafe { renderer.draw(view_proj, cam_pos, bone_arrays, skin_rgb, count) };
        }
    }
}

/// Try the Vulkan backend for `window`.
pub unsafe fn try_vulkan(
    window: *mut crate::vulkan::context::ANativeWindow,
    width: i32,
    height: i32,
    state_width: u32,
    state_height: u32,
) -> Result<Renderer, String> {
    Renderer::try_vulkan_only(window, width, height, state_width, state_height)
}

/// Tear down the renderer, stop the thread. Traits/pose survive.
pub fn destroy_renderer() {
    if let Ok(mut eng) = engine().lock() {
        if let Some(mut renderer) = eng.renderer.take() {
            unsafe { renderer.destroy() };
        }
        eng.mesh_count = 0;
    }
}

/// Bump the render-thread generation (rotation path) and return it.
pub fn next_generation() -> u64 {
    if let Ok(mut eng) = engine().lock() {
        eng.generation = eng.generation.wrapping_add(1);
        eng.generation
    } else {
        0
    }
}

/// Current render-thread generation.
pub fn generation() -> u64 {
    engine().lock().map(|e| e.generation).unwrap_or(0)
}

/// Destroy everything.
pub fn destroy_engine() {
    destroy_renderer();
}
