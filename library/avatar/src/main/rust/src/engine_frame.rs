// Render path: backend logging, preview camera, surface creation, mesh
// upload, resize, per-frame tick, and the Vulkan-only constructor helper.
//
// Split out of `engine.rs` so each file stays under the 500-line Rust limit.
// Same module scope via `engine.rs` (`include!`), so item order is irrelevant.

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

/// Create the renderer for `window` on a surface and upload the mesh.
///
/// Procedural surfaces get a mesh built from their own traits (decal UVs are
/// trait-dependent); bundled-glb surfaces assemble base body + attachments
/// from their own traits (attachments are trait-selected).
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
        let parsed_pack = GltfPack::parse(&bytes);
        let fallback_mesh = load_gltf_mesh(&bytes);
        if let Ok(mut eng) = engine().lock() {
            eng.pack = parsed_pack;
            eng.mesh = Arc::new(fallback_mesh);
        }
    }
    let w = width.max(1) as u32;
    let h = height.max(1) as u32;
    // Build this surface's mesh: assembled pack when bundled, trait-specific
    // procedural mesh otherwise (done outside the tick path — creation is rare).
    let mesh: Vec<YuuVertex> = engine()
        .lock()
        .map(|e| {
            let traits = e
                .surfaces
                .get(&surface_id)
                .map(|s| s.traits.clone())
                .unwrap_or_default();
            match (&e.pack, &e.bundled_mesh) {
                (Some(pack), Some(_)) => assemble_pack(pack, &traits)
                    .unwrap_or_else(|| build_avatar_mesh(&crate::traits::YuuTraits::default())),
                _ => {
                    if e.bundled_mesh.is_some() {
                        e.mesh.to_vec()
                    } else {
                        build_avatar_mesh(&traits)
                    }
                }
            }
        })
        .unwrap_or_else(|_| build_avatar_mesh(&crate::traits::YuuTraits::default()));
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
        state.mesh_dirty = false;
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
    // GltfPack is not Clone/Copy (it owns parsed verts); assemble the
    // trait-selected mesh OUTSIDE the lock (allocation must not stall ticks),
    // then hand it to the render thread like the procedural remesh.
    let pack_dirty = engine()
        .lock()
        .map(|e| e.surfaces.get(&surface_id).is_some_and(|s| s.mesh_dirty) && e.pack.is_some())
        .unwrap_or(false);
    let pack_mesh = if pack_dirty {
        engine().lock().ok().and_then(|e| {
            let state = e.surfaces.get(&surface_id)?;
            let pack = e.pack.as_ref()?;
            assemble_pack(pack, &state.traits)
        })
    } else {
        None
    };
    let (view_proj, cam_pos, bones, skin_rgb, count, paused, atlas, remesh) = {
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
        // Regenerate the face atlas when traits changed (config path sets the
        // flag; generation is cheap CPU work done once per change, not per
        // frame — upload happens below on the render thread).
        let atlas = if state.atlas_dirty {
            Some(crate::atlas::generate_atlas(&state.traits))
        } else {
            None
        };
        // Rebuild the mesh when traits changed: packed-glb surfaces use the
        // trait-selected assembly computed above; procedural surfaces rebuild
        // (decal UVs are trait-dependent); legacy bundled surfaces without a
        // parsed pack keep the shared asset mesh.
        let has_pack = eng.pack.is_some();
        let remesh = if state.mesh_dirty {
            match &pack_mesh {
                Some(mesh) => Some(mesh.clone()),
                None if has_pack => None,
                None => Some(build_avatar_mesh(&state.traits)),
            }
        } else {
            None
        };
        (vp, cp, bones, skin, state.mesh_count, state.paused, atlas, remesh)
    };
    if paused {
        return;
    }
    // Bone matrices as nested arrays for the draw call.
    let bone_arrays: [[f32; 16]; MAX_BONES] = bones.map(|m| m.to_cols_array());
    if let Ok(mut eng) = engine().lock() {
        if let Some(state) = eng.surfaces.get_mut(&surface_id) {
            if let Some(renderer) = state.renderer.as_mut() {
                // Packed-glb re-assembly rides the same upload path; the flag
                // clears on every attempt (success or fallback) so a failed
                // assembly can't thrash the upload every frame.
                if state.mesh_dirty {
                    if let Some(mesh) = pack_mesh.or(remesh) {
                        unsafe { renderer.upload_avatar(&mesh) };
                        state.mesh_count = mesh.len();
                    }
                    state.mesh_dirty = false;
                }
                if let Some(pixels) = atlas {
                    if unsafe { renderer.upload_atlas(&pixels) }.is_ok() {
                        state.atlas_dirty = false;
                    }
                }
                let _ = unsafe { renderer.draw(view_proj, cam_pos, bone_arrays, skin_rgb, count) };
            }
        }
    }
}

/// Try the Vulkan backend for `window`.
///
/// # Safety
/// `window` must be a live adopted `ANativeWindow`; ownership stays with the
/// engine (released on destroy), as on the kart path.
pub unsafe fn try_vulkan(
    window: *mut crate::vulkan::context::ANativeWindow,
    width: i32,
    height: i32,
    state_width: u32,
    state_height: u32,
) -> Result<Renderer, String> {
    Renderer::try_vulkan_only(window, width, height, state_width, state_height)
}
