// Surface config setters: traits, pose, orbit prefs, renderer presence.
//
// Split out of `engine.rs` so each file stays under the 500-line Rust limit.
// Same module scope via `engine.rs` (`include!`), so item order is irrelevant.

/// Replace a surface's traits from `YuuFaceConfig` JSON.
pub fn set_traits_json(surface_id: i32, json: &str) {
    if let Ok(mut eng) = engine().lock() {
        let state = eng.surface_mut(surface_id, 512, 512);
        state.traits = YuuTraits::from_config_json(json);
        // Face atlas depends on traits — the tick thread re-uploads it.
        state.atlas_dirty = true;
        // Assembled mesh depends on traits both ways: procedural decal UVs are
        // trait-dependent, and packed attachments are trait-selected — the
        // tick thread rebuilds + re-uploads the mesh either way.
        state.mesh_dirty = true;
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
