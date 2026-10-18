//! GLSL ES program: compile + link the `kart_gles` shader twins.
//!
//! The Vulkan SPIR-V pair gains GLSL ES 3.0 sources (built untouched by
//! `build.rs`, which only knows `kart.vert`/`kart.frag`); the UBO becomes
//! three uniforms (`uViewProj` / `uSunDir` / `uCamPos`). Every failure
//! surfaces the driver info log so logcat names the bad line, not just
//! "link failed".

use glow::HasContext as _;

/// Vertex shader: `#version 300 es`, Vulkan `clip.y` flip removed.
pub const VERT_SRC: &str = include_str!("../../shaders/kart_gles.vert");
/// Fragment shader: `#version 300 es`, same `ndl` + `exp` fog `0.0045`.
pub const FRAG_SRC: &str = include_str!("../../shaders/kart_gles.frag");

/// View-projection uniform (mirrors the Vulkan UBO's `viewProj`).
pub const U_VIEW_PROJ: &str = "uViewProj";
/// Fixed sun direction (mirrors the Vulkan UBO's `sunDir`).
pub const U_SUN_DIR: &str = "uSunDir";
/// Chase-cam position, the fog origin (mirrors the Vulkan UBO's `camPos`).
pub const U_CAM_POS: &str = "uCamPos";

/// Compile `VERT_SRC` + `FRAG_SRC`, link, return the program. On any failure
/// the returned string carries the shader/program info log from the driver.
pub unsafe fn compile_program(gl: &glow::Context) -> Result<glow::Program, String> {
    let vert = unsafe { compile_stage(gl, glow::VERTEX_SHADER, "kart_gles.vert", VERT_SRC)? };
    let frag = unsafe { compile_stage(gl, glow::FRAGMENT_SHADER, "kart_gles.frag", FRAG_SRC)? };
    let program = unsafe { gl.create_program() }.map_err(|e| format!("glCreateProgram: {e}"))?;
    unsafe {
        gl.attach_shader(program, vert);
        gl.attach_shader(program, frag);
        gl.link_program(program);
        gl.delete_shader(vert);
        gl.delete_shader(frag);
    }
    if !unsafe { gl.get_program_link_status(program) } {
        let log = unsafe { gl.get_program_info_log(program) };
        unsafe { gl.delete_program(program) };
        return Err(format!("glLinkProgram kart_gles: {log}"));
    }
    Ok(program)
}

/// Compile one shader stage; the error carries the driver's info log.
unsafe fn compile_stage(
    gl: &glow::Context,
    stage: u32,
    name: &str,
    src: &str,
) -> Result<glow::Shader, String> {
    let shader = unsafe { gl.create_shader(stage) }
        .map_err(|e| format!("glCreateShader {name}: {e}"))?;
    unsafe {
        gl.shader_source(shader, src);
        gl.compile_shader(shader);
    }
    if !unsafe { gl.get_shader_compile_status(shader) } {
        let log = unsafe { gl.get_shader_info_log(shader) };
        unsafe { gl.delete_shader(shader) };
        return Err(format!("glCompileShader {name}: {log}"));
    }
    Ok(shader)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn gles_shaders_are_300_es_with_matching_uniforms() {
        for src in [VERT_SRC, FRAG_SRC] {
            assert!(
                src.trim_start().starts_with("#version 300 es"),
                "missing ES 3.0 preamble"
            );
        }
        assert!(
            !VERT_SRC.contains("clip.y = -clip.y"),
            "Vulkan Y flip must not ship on GLES"
        );
        for uniform in [U_VIEW_PROJ, U_SUN_DIR, U_CAM_POS] {
            assert!(VERT_SRC.contains(uniform), "vert missing {uniform}");
        }
        for uniform in [U_SUN_DIR, U_CAM_POS] {
            assert!(FRAG_SRC.contains(uniform), "frag missing {uniform}");
        }
    }
}
