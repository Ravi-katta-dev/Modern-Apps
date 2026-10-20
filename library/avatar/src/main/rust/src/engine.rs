//! Avatar engine: per-surface lifecycle, preview camera, per-frame bone upload.
//!
//! The engine owns a map of live surfaces (`surfaceId -> SurfaceState`), each
//! with its own renderer, render thread generation, traits + pose, and size.
//! The CPU mesh bytes are shared across surfaces (single `Arc` allocation);
//! GPU buffers + UBO/pose stay per-surface. The render threads tick here;
//! [`tick_surface`] uploads the bone-matrix UBO and draws one surface per call.

use crate::avatar::{compute_bone_matrices, YuuTraits, MAX_BONES};
use crate::pose::{preset_by_id, preset_poses, YuuPose};
use crate::mesh::{build_avatar_mesh, load_gltf_mesh, YuuVertex};
use crate::renderer::Renderer;
use glam::Mat4;
use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Instant;

/// Per-surface state: one renderer + thread + config behind one surface id.
pub struct SurfaceState {
    /// Active GPU backend, if the surface is live.
    pub renderer: Option<Renderer>,
    /// Current trait mapping (from `YuuFaceConfig` JSON).
    pub traits: YuuTraits,
    /// Current pose preset.
    pub pose: YuuPose,
    /// Turntable clock start.
    pub started: Instant,
    /// Vertex count from the last upload.
    pub mesh_count: usize,
    /// Surface width for the camera aspect.
    pub width: u32,
    /// Surface height for the camera aspect.
    pub height: u32,
    /// Paused (surface alive but hidden): skip draws, keep state.
    pub paused: bool,
    /// Render-thread generation: bumped on recreate so a stale thread
    /// (rotation) exits instead of racing the new one.
    pub generation: u64,
    /// User-driven orbit yaw radians (unlimited). Used when `autoRotate` is
    /// false (editor); heroes keep the time-based turntable.
    pub yaw: f32,
    /// User-driven orbit pitch radians, clamped to ±MAX_PITCH.
    pub pitch: f32,
    /// Whether the camera auto-orbits. The editor sets false on creation.
    pub auto_rotate: bool,
}

/// Max orbit pitch: enough to inspect top/bottom, never straight down/up.
pub const MAX_PITCH: f32 = 0.6;

impl SurfaceState {
    /// Fresh surface with the idle pose.
    fn new(width: u32, height: u32) -> Self {
        Self {
            renderer: None,
            traits: YuuTraits::default(),
            pose: preset_poses().into_iter().next().unwrap_or(YuuPose {
                id: "idle".into(),
                name: "Idle".into(),
                joints: Default::default(),
            }),
            started: Instant::now(),
            mesh_count: 0,
            width: width.max(1),
            height: height.max(1),
            paused: false,
            generation: 0,
            yaw: 0.0,
            pitch: 0.0,
            auto_rotate: true,
        }
    }
}

/// User orbit preference: survives surface removal (rotation) so the editor
/// keeps its drag position and auto-rotate opt-out.
#[derive(Clone, Copy, Debug)]
struct OrbitPrefs {
    yaw: f32,
    pitch: f32,
    auto_rotate: bool,
}

impl Default for OrbitPrefs {
    fn default() -> Self {
        Self { yaw: 0.0, pitch: 0.0, auto_rotate: true }
    }
}

/// Engine state behind the global lock: the surface map plus shared assets.
pub struct Engine {
    /// Live surfaces by Kotlin-owned surface id.
    pub surfaces: HashMap<i32, SurfaceState>,
    /// Orbit prefs by surface id: written on input, restored on (re)create,
    /// never cleared by surface removal.
    pub orbit: HashMap<i32, OrbitPrefs>,
    /// Shared CPU mesh bytes (procedural fallback or parsed glb), uploaded
    /// per-surface to GPU. Single allocation across all surfaces.
    pub mesh: Arc<Vec<YuuVertex>>,
    /// Bundled glb bytes, when the APK ships `base_upperbody.glb`.
    pub bundled_mesh: Option<Vec<u8>>,
}

impl Engine {
    /// Fresh engine with the procedural fallback mesh.
    pub fn new() -> Self {
        Self {
            surfaces: HashMap::new(),
            orbit: HashMap::new(),
            mesh: Arc::new(build_avatar_mesh()),
            bundled_mesh: None,
        }
    }

    /// Get or create the state for a surface.
    fn surface_mut(&mut self, surface_id: i32, width: u32, height: u32) -> &mut SurfaceState {
        self.surfaces
            .entry(surface_id)
            .or_insert_with(|| SurfaceState::new(width, height))
    }
}

impl Default for Engine {
    fn default() -> Self {
        Self::new()
    }
}

// SAFETY: guarded by the mutex; each renderer (Vulkan/GLES handles) is only
// touched on its own surface's render thread, same arrangement as games/kart.
unsafe impl Send for Engine {}

static ENGINE: OnceLock<Mutex<Engine>> = OnceLock::new();

fn engine() -> &'static Mutex<Engine> {
    ENGINE.get_or_init(|| Mutex::new(Engine::new()))
}

/// Adopt the engine state (idempotent).
pub fn ensure_engine() {
    let _ = engine();
}

/// Replace a surface's traits from `YuuFaceConfig` JSON.
pub fn set_traits_json(surface_id: i32, json: &str) {
    if let Ok(mut eng) = engine().lock() {
        eng.surface_mut(surface_id, 512, 512).traits = YuuTraits::from_config_json(json);
    }
}

/// Replace a surface's pose by preset id (unknown ids keep the old pose).
pub fn set_pose_id(surface_id: i32, pose_id: &str) {
    if let Some(preset) = preset_by_id(pose_id) {
        if let Ok(mut eng) = engine().lock() {
            eng.surface_mut(surface_id, 512, 512).pose = preset;
        }
    }
}

/// Set both traits and pose from JSON in one call (surface creation path).
pub fn set_config(surface_id: i32, config_json: &str, pose_json: &str) {
    set_traits_json(surface_id, config_json);
    // Pose arrives either as a bare id string or `{"id": ...}`.
    let id = pose_json.trim().trim_matches('"').to_string();
    let id = serde_json::from_str::<serde_json::Value>(pose_json)
        .ok()
        .and_then(|v| v.get("id").and_then(|s| s.as_str()).map(str::to_string))
        .unwrap_or(id);
    if !id.is_empty() {
        set_pose_id(surface_id, &id);
    }
}

/// Provide bundled glb bytes (from APK assets via JNI); the next surface
/// creation parses them into the shared mesh.
pub fn set_bundled_mesh(bytes: Vec<u8>) {
    if let Ok(mut eng) = engine().lock() {
        eng.bundled_mesh = Some(bytes);
    }
}

/// Pause or resume drawing on a surface (keeps the renderer alive).
pub fn set_paused(surface_id: i32, paused: bool) {
    if let Ok(mut eng) = engine().lock() {
        if let Some(state) = eng.surfaces.get_mut(&surface_id) {
            state.paused = paused;
        }
    }
}

/// Accumulate a user drag-rotate delta on a surface. Yaw is unlimited;
/// pitch clamps to ±MAX_PITCH so the camera never goes straight down/up.
/// The avatar stays where left (no snap-back). Prefs persist across surface
/// removal so rotation keeps the orbit.
pub fn on_rotate_input(surface_id: i32, yaw_delta: f32, pitch_delta: f32) {
    if let Ok(mut eng) = engine().lock() {
        let prefs = eng.orbit.entry(surface_id).or_default();
        prefs.yaw += yaw_delta;
        prefs.pitch = (prefs.pitch + pitch_delta).clamp(-MAX_PITCH, MAX_PITCH);
        let (yaw, pitch) = (prefs.yaw, prefs.pitch);
        if let Some(state) = eng.surfaces.get_mut(&surface_id) {
            state.yaw = yaw;
            state.pitch = pitch;
        }
    }
}

/// Set whether a surface auto-orbits. The editor disables it on creation so
/// drag fully owns the camera; heroes leave it on. Never creates a phantom
/// surface entry — prefs persist and apply on (re)create.
pub fn set_auto_rotate(surface_id: i32, auto_rotate: bool) {
    if let Ok(mut eng) = engine().lock() {
        eng.orbit.entry(surface_id).or_default().auto_rotate = auto_rotate;
        if let Some(state) = eng.surfaces.get_mut(&surface_id) {
            state.auto_rotate = auto_rotate;
        }
    }
}

/// Whether a surface's renderer is currently live.
pub fn has_renderer(surface_id: i32) -> bool {
    engine()
        .lock()
        .map(|e| e.surfaces.get(&surface_id).is_some_and(|s| s.renderer.is_some()))
        .unwrap_or(false)
}

/// Whether a surface's render thread should keep running.
pub fn is_running(surface_id: i32) -> bool {
    has_renderer(surface_id)
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

/// Preview camera: fixed-radius orbit looking at the upper body.
///
/// Mirrors kart `view_proj_for` (look_at_rh + perspective_rh via glam). Heroes
/// use a slow time-based orbit so they show depth; the editor drives `yaw` /
/// `pitch` by drag and the avatar stays where left. Pitch raises/lowers the
/// eye (clamped native-side on input).
pub fn view_proj_for(
    width: u32,
    height: u32,
    time_s: f32,
    yaw: f32,
    pitch: f32,
    auto_rotate: bool,
) -> ([[f32; 4]; 4], [f32; 3]) {
    let aspect = width.max(1) as f32 / height.max(1) as f32;
    let angle = if auto_rotate { time_s * 0.25 } else { yaw };
    // Framing: avatar spans y 0.47..1.43 (~1m); radius 3.4 + 44° FOV keeps
    // the head inside small square surfaces (plaza cells, badges) with margin.
    let radius = 3.4;
    let eye = glam::Vec3::new(
        angle.sin() * radius,
        1.25 + pitch * radius * 0.5,
        angle.cos() * radius,
    );
    let center = glam::Vec3::new(0.0, 0.9, 0.0);
    let up = glam::Vec3::Y;
    let view = Mat4::look_at_rh(eye, center, up);
    let proj = Mat4::perspective_rh_gl(44.0f32.to_radians(), aspect, 0.1, 20.0);
    let view_proj = proj * view;
    (view_proj.to_cols_array_2d(), eye.to_array())
}

/// Create the renderer for `window` on a surface and upload the shared mesh.
///
/// # Safety
/// `window` must be a live adopted `ANativeWindow`; ownership stays with the
/// engine (released on destroy), as on the kart path.
pub unsafe fn create_renderer(
    surface_id: i32,
    window: *mut crate::vulkan::context::ANativeWindow,
    width: i32,
    height: i32,
) -> bool {
    // Parse outside the lock: glTF parsing allocates and must not stall tick
    // threads waiting on the engine mutex.
    let bundled: Option<Vec<u8>> = engine().lock().ok().and_then(|e| e.bundled_mesh.clone());
    if let Some(bytes) = bundled {
        let parsed = load_gltf_mesh(&bytes);
        if let Ok(mut eng) = engine().lock() {
            eng.mesh = Arc::new(parsed);
        }
    }
    let mesh: Arc<Vec<YuuVertex>> = engine()
        .lock()
        .map(|e| Arc::clone(&e.mesh))
        .unwrap_or_else(|_| Arc::new(build_avatar_mesh()));
    let w = width.max(1) as u32;
    let h = height.max(1) as u32;
    if let Ok(mut eng) = engine().lock() {
        let prefs = eng.orbit.get(&surface_id).copied().unwrap_or_default();
        let state = eng.surface_mut(surface_id, w, h);
        state.width = w;
        state.height = h;
        state.paused = false;
        // Restore the orbit across recreation (rotation keeps drag position
        // and the editor's auto-rotate opt-out).
        state.yaw = prefs.yaw;
        state.pitch = prefs.pitch;
        state.auto_rotate = prefs.auto_rotate;
    }
    let renderer = match unsafe { Renderer::select(window, width, height, w, h) } {
        Ok(r) => r,
        Err(_) => return false,
    };
    if let Ok(mut eng) = engine().lock() {
        if let Some(state) = eng.surfaces.get_mut(&surface_id) {
            state.renderer = Some(renderer);
            state.mesh_count = mesh.len();
        }
    }
    // Upload on the render thread with the backend's context current.
    upload_mesh(surface_id, &mesh)
}

/// Upload the shared mesh to one surface's renderer.
pub fn upload_mesh(surface_id: i32, mesh: &[YuuVertex]) -> bool {
    let mut eng = match engine().lock() {
        Ok(e) => e,
        Err(_) => return false,
    };
    if let Some(state) = eng.surfaces.get_mut(&surface_id) {
        if let Some(renderer) = state.renderer.as_mut() {
            unsafe { renderer.upload_avatar(mesh) };
            state.mesh_count = mesh.len();
            true
        } else {
            false
        }
    } else {
        false
    }
}

/// Resize one surface's swapchain.
pub fn resize_renderer(surface_id: i32, width: i32, height: i32) {
    if let Ok(mut eng) = engine().lock() {
        let w = width.max(1) as u32;
        let h = height.max(1) as u32;
        if let Some(state) = eng.surfaces.get_mut(&surface_id) {
            state.width = w;
            state.height = h;
            if let Some(renderer) = state.renderer.as_mut() {
                unsafe { renderer.resize(w, h) };
            }
        }
    }
}

/// One tick for one surface: compute bones, upload the UBO, draw.
pub fn tick_surface(surface_id: i32) {
    let (view_proj, cam_pos, bones, skin_rgb, count, paused) = {
        let eng = match engine().lock() {
            Ok(e) => e,
            Err(_) => return,
        };
        let Some(state) = eng.surfaces.get(&surface_id) else {
            return;
        };
        if state.renderer.is_none() || state.paused {
            return;
        }
        let t = state.started.elapsed().as_secs_f32();
        let (vp, cp) = view_proj_for(
            state.width,
            state.height,
            t,
            state.yaw,
            state.pitch,
            state.auto_rotate,
        );
        let bones = compute_bone_matrices(&state.traits, &state.pose, t);
        let skin = state.traits.skin_rgb();
        (vp, cp, bones, skin, state.mesh_count, state.paused)
    };
    if paused {
        return;
    }
    // Bone matrices as nested arrays for the draw call.
    let bone_arrays: [[f32; 16]; MAX_BONES] = bones.map(|m| m.to_cols_array());
    if let Ok(mut eng) = engine().lock() {
        if let Some(state) = eng.surfaces.get_mut(&surface_id) {
            if let Some(renderer) = state.renderer.as_mut() {
                let _ = unsafe { renderer.draw(view_proj, cam_pos, bone_arrays, skin_rgb, count) };
            }
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

/// Tear down one surface's renderer, stop its thread. Traits/pose survive in
/// the state until the surface entry is removed.
pub fn destroy_renderer(surface_id: i32) {
    if let Ok(mut eng) = engine().lock() {
        if let Some(state) = eng.surfaces.get_mut(&surface_id) {
            if let Some(mut renderer) = state.renderer.take() {
                unsafe { renderer.destroy() };
            }
            state.mesh_count = 0;
        }
    }
}

/// Remove a surface entry entirely (after its thread has exited).
pub fn remove_surface(surface_id: i32) {
    if let Ok(mut eng) = engine().lock() {
        eng.surfaces.remove(&surface_id);
    }
}

/// Bump a surface's render-thread generation (rotation path) and return it.
pub fn next_generation(surface_id: i32) -> u64 {
    if let Ok(mut eng) = engine().lock() {
        let state = eng.surface_mut(surface_id, 512, 512);
        state.generation = state.generation.wrapping_add(1);
        state.generation
    } else {
        0
    }
}

/// A surface's current render-thread generation.
pub fn generation(surface_id: i32) -> u64 {
    engine()
        .lock()
        .map(|e| e.surfaces.get(&surface_id).map(|s| s.generation).unwrap_or(0))
        .unwrap_or(0)
}

/// Destroy everything.
pub fn destroy_engine() {
    if let Ok(mut eng) = engine().lock() {
        for (_, state) in eng.surfaces.iter_mut() {
            if let Some(mut renderer) = state.renderer.take() {
                unsafe { renderer.destroy() };
            }
            state.mesh_count = 0;
        }
        eng.surfaces.clear();
    }
}
