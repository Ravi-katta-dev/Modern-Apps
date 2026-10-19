//! Frame renderer: EGL surface, glow program, skinned VAO/VBO, draw + present.
//!
//! The GLES twin of [`crate::vulkan::renderer::YuuRenderer`]: `new` brings up
//! EGL, loads glow from `eglGetProcAddress`, compiles/links the program,
//! creates the avatar [`MeshBuffer`] and uploads the static mesh, sets
//! viewport + `clear_color(SKY)` + depth `LESS` + cull off; `draw` clears,
//! sets uniforms (view-proj, sun, cam, bones), draws the mesh, then
//! `swap_buffers` (no fences — the swap is the present). `resize` re-creates
//! the EGL surface on the same context (GL objects survive); `destroy` frees
//! GL objects then tears down EGL. The avatar mesh uploads once per surface,
//! exactly like Vulkan.

use crate::avatar::MAX_BONES;
use crate::gles::buffers::{self, MeshBuffer};
use crate::gles::egl::Egl;
use crate::gles::shaders::{self, U_ALBEDO, U_BONES, U_CAM_POS, U_SUN_DIR, U_VIEW_PROJ};
use crate::mesh::YuuVertex;
use glow::HasContext as _;

/// Static avatar mesh budget: the procedural fallback is ~1k verts; 64k
/// leaves margin for a detailed glTF base mesh.
pub const AVATAR_VERT_CAP: usize = 65_536;

/// Sky clear color, shared with the Vulkan clear value and the frag shader.
pub const SKY: [f32; 3] = [0.45, 0.68, 0.94];

/// Fixed sun direction (normalized on upload — mirrors the Vulkan UBO fill).
const SUN_DIR: [f32; 3] = [0.35, -1.0, 0.45];

/// Everything needed to draw one GLES frame.
pub struct GlesRenderer {
    /// EGL display/surface/context (RAII; owns the presentation surface).
    egl: Egl,
    /// glow context (function pointers loaded via `eglGetProcAddress`).
    gl: glow::Context,
    /// Linked `yuu_avatar_gles` program.
    program: glow::Program,
    /// `uViewProj` uniform location.
    u_view_proj: glow::UniformLocation,
    /// `uSunDir` uniform location.
    u_sun_dir: glow::UniformLocation,
    /// `uCamPos` uniform location.
    u_cam_pos: glow::UniformLocation,
    /// `uBones` uniform location.
    u_bones: glow::UniformLocation,
    /// Static avatar VAO/VBO (uploaded per surface).
    avatar: MeshBuffer,
    /// Current surface width.
    pub width: u32,
    /// Current surface height.
    pub height: u32,
}

impl GlesRenderer {
    /// Create from a live window at `width` x `height`. Fails cleanly (the
    /// caller logs and keeps the 2D fallback) rather than panicking.
    ///
    /// # Safety
    /// `window` must be a live `ANativeWindow` adopted from the JNI surface
    /// (the caller acquired it via `ANativeWindow_acquire`); ownership moves
    /// to the engine, exactly as on the Vulkan path.
    pub unsafe fn new(
        window: *mut crate::vulkan::context::ANativeWindow,
        width: u32,
        height: u32,
    ) -> Result<Self, String> {
        let egl = unsafe { Egl::new(window)? };
        // Validate the loader BEFORE the first real GL call: glow loads
        // lazily, so a null entry point only explodes inside the driver
        // (SIGSEGV in libGLES_mali, no EGL error). Fail to Err here and the
        // caller keeps the fallback instead of crashing.
        for name in [
            "glCreateShader",
            "glShaderSource",
            "glCompileShader",
            "glCreateProgram",
            "glLinkProgram",
            "glGenBuffers",
            "glBindBuffer",
            "glBufferData",
            "glGenVertexArrays",
            "glBindVertexArray",
            "glViewport",
            "glClearColor",
            "glClear",
        ] {
            egl.require_proc(name)?;
        }
        let gl = unsafe {
            glow::Context::from_loader_function(|name| egl.proc_address(name) as *const _)
        };
        let program = unsafe { shaders::compile_program(&gl)? };
        crate::gles::egl::step_log("program compiled+linked");
        let (u_view_proj, u_sun_dir, u_cam_pos, u_bones) = unsafe {
            let view = gl
                .get_uniform_location(program, U_VIEW_PROJ)
                .ok_or_else(|| format!("missing uniform {U_VIEW_PROJ}"))?;
            let sun = gl
                .get_uniform_location(program, U_SUN_DIR)
                .ok_or_else(|| format!("missing uniform {U_SUN_DIR}"))?;
            let cam = gl
                .get_uniform_location(program, U_CAM_POS)
                .ok_or_else(|| format!("missing uniform {U_CAM_POS}"))?;
            let bones = gl
                .get_uniform_location(program, U_BONES)
                .ok_or_else(|| format!("missing uniform {U_BONES}"))?;
            // Albedo must exist even though v1 binds no texture yet (Mali
            // linkers dislike dead cross-stage uniforms, so the frag keeps a
            // 1x1 default bound at draw time).
            let _ = gl.get_uniform_location(program, U_ALBEDO);
            (view, sun, cam, bones)
        };
        crate::gles::egl::step_log("uniforms located");
        let avatar = unsafe { MeshBuffer::new(&gl)? };
        crate::gles::egl::step_log("avatar MeshBuffer created");
        let renderer = Self {
            egl,
            gl,
            program,
            u_view_proj,
            u_sun_dir,
            u_cam_pos,
            u_bones,
            avatar,
            width: width.max(1),
            height: height.max(1),
        };
        unsafe { renderer.apply_state() };
        Ok(renderer)
    }

    /// Upload the static avatar mesh (once per surface; capped at the cap).
    ///
    /// # Safety
    /// Must be called on the render thread with the EGL context current.
    pub unsafe fn upload_avatar(&mut self, mesh: &[YuuVertex]) {
        unsafe { buffers::upload_avatar_mesh(&self.gl, &mut self.avatar, mesh) };
    }

    /// Draw one frame: clear, set uniforms (view-proj, sun, cam, bones),
    /// draw the avatar, `eglSwapBuffers`.
    ///
    /// # Safety
    /// Must be called on the render thread with the EGL context current.
    pub unsafe fn draw(
        &mut self,
        view_proj: [[f32; 4]; 4],
        cam_pos: [f32; 3],
        bones: [[f32; 16]; MAX_BONES],
        _skin_rgb: [f32; 3],
        _mesh_count: usize,
    ) -> Result<(), String> {
        let flat = flatten(&view_proj);
        let sun = normalize(SUN_DIR);
        // Column-major bone flatten for `uniform_matrix_4_fv(transpose=false)`.
        let mut bone_flat = [0.0f32; MAX_BONES * 16];
        for (i, b) in bones.iter().enumerate() {
            bone_flat[i * 16..(i + 1) * 16].copy_from_slice(b);
        }
        unsafe {
            self.gl
                .clear_color(SKY[0], SKY[1], SKY[2], 1.0);
            self.gl.clear(glow::COLOR_BUFFER_BIT | glow::DEPTH_BUFFER_BIT);
            self.gl.use_program(Some(self.program));
            self.gl
                .uniform_matrix_4_f32_slice(Some(&self.u_view_proj), false, &flat);
            self.gl
                .uniform_3_f32(Some(&self.u_sun_dir), sun[0], sun[1], sun[2]);
            self.gl
                .uniform_3_f32(Some(&self.u_cam_pos), cam_pos[0], cam_pos[1], cam_pos[2]);
            self.gl.uniform_matrix_4_f32_slice(Some(&self.u_bones), false, &bone_flat);
        }
        unsafe {
            self.avatar.draw(&self.gl);
            self.gl.use_program(None);
        }
        self.egl.swap_buffers()
    }

    /// Re-create the EGL surface at the new size on the same context (GL
    /// objects survive, so no re-upload). On failure the caller drops the
    /// renderer and the 2D fallback covers, as on Vulkan.
    ///
    /// # Safety
    /// `window` must be the live adopted `ANativeWindow`; render thread only.
    pub unsafe fn resize(&mut self, width: u32, height: u32) {
        self.width = width.max(1);
        self.height = height.max(1);
        unsafe { self.apply_state() };
    }

    /// Free program + VAO/VBOs, then tear down EGL. The engine releases the
    /// window itself.
    ///
    /// # Safety
    /// Must be called on the render thread that owns the EGL context.
    pub unsafe fn destroy(&mut self) {
        unsafe {
            self.gl.use_program(None);
            self.avatar.destroy(&self.gl);
            self.gl.delete_program(self.program);
        }
        self.egl.teardown();
    }

    /// Viewport + fixed GL state: depth test `LESS` (matches the Vulkan
    /// pipeline), cull off (matches `CULL_NONE` — winding is as authored
    /// since the GLES shaders carry no Y flip).
    unsafe fn apply_state(&self) {
        unsafe {
            self.gl
                .viewport(0, 0, self.width as i32, self.height as i32);
            self.gl.enable(glow::DEPTH_TEST);
            self.gl.depth_func(glow::LESS);
            self.gl.disable(glow::CULL_FACE);
        }
    }
}

/// Column-major flatten for `uniform_matrix_4_f32_slice(transpose=false)`:
/// the `[[f32; 4]; 4]` from [`crate::engine::view_proj_for`] is already
/// `to_cols_array_2d`, so concatenation preserves column order.
fn flatten(m: &[[f32; 4]; 4]) -> [f32; 16] {
    let mut out = [0.0f32; 16];
    for (col, column) in m.iter().enumerate() {
        for (row, v) in column.iter().enumerate() {
            out[col * 4 + row] = *v;
        }
    }
    out
}

/// Normalize the fixed sun direction (mirrors the Vulkan UBO fill).
fn normalize(v: [f32; 3]) -> [f32; 3] {
    let len = (v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).sqrt().max(1e-6);
    [v[0] / len, v[1] / len, v[2] / len]
}
