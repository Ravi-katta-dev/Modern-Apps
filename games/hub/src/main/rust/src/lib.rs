//! yuu_avatar: glTF upper-body avatar with skeletal skinning + preset poses.
//!
//! The JNI surface mirrors the Kotlin `YuuAvatarNative` object exactly: FQNs
//! are frozen (`com.vayunmathur.games.hub.util.YuuAvatarNative`), so the
//! package exception marker on the Kotlin file covers this seam too. jni 0.22
//! API: native methods receive `EnvUnowned` and adapt via `with_env`.

pub mod avatar;
pub mod engine;
pub mod gles;
pub mod mesh;
pub mod renderer;
pub mod vulkan;

use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jint, jstring};
use jni::{Env, EnvUnowned};

#[cfg(target_os = "android")]
use std::os::raw::c_void as CVoid;

fn get_string(env: &Env, s: &JString) -> Option<String> {
    s.try_to_string(env).ok()
}

/// Adopt the engine state (idempotent; called once from native init).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_hub_util_YuuAvatarNative_nativeInit<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
) -> jboolean {
    let _ = &mut env;
    engine::ensure_engine();
    true
}

/// Set traits + pose from JSON (`YuuFaceConfig` JSON, pose id or JSON).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_hub_util_YuuAvatarNative_nativeSetConfig<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    config_json: JString<'l>,
    pose_json: JString<'l>,
) {
    engine::ensure_engine();
    let (config, pose) = env
        .with_env(|env| -> jni::errors::Result<(String, String)> {
            Ok((
                get_string(env, &config_json).unwrap_or_default(),
                get_string(env, &pose_json).unwrap_or_default(),
            ))
        })
        .resolve::<jni::errors::LogErrorAndDefault>();
    engine::set_config(&config, &pose);
}

/// Surface ready: adopt the window, build the renderer, start the 60 Hz
/// render thread. A second call (rotation) destroys the old renderer first
/// and bumps the thread generation so the stale thread exits instead of
/// racing the new one.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_hub_util_YuuAvatarNative_nativeSurfaceCreated<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    surface: JObject<'l>,
) {
    engine::ensure_engine();
    #[cfg(target_os = "android")]
    {
        let env_raw = env.as_raw() as *mut CVoid;
        let surf_raw = surface.as_raw() as *mut CVoid;
        let window = unsafe { vulkan::context::ANativeWindow_fromSurface(env_raw, surf_raw) };
        if window.is_null() {
            return;
        }
        unsafe { vulkan::context::ANativeWindow_acquire(window) };
        // Rotation path: tear down the previous renderer + window before
        // adopting the new surface, so at most one render thread runs.
        engine::destroy_renderer();
        let generation = engine::next_generation();
        if !unsafe { engine::create_renderer(window, 1280, 720) } {
            unsafe { vulkan::context::ANativeWindow_release(window) };
            return;
        }
        std::thread::spawn(move || {
            let mut last = std::time::Instant::now();
            loop {
                if !engine::has_renderer() || engine::generation() != generation {
                    break;
                }
                engine::tick_and_render();
                let elapsed = last.elapsed();
                let target = std::time::Duration::from_millis(16);
                if elapsed < target {
                    std::thread::sleep(target - elapsed);
                }
                last = std::time::Instant::now();
                if !engine::is_running() || engine::generation() != generation {
                    break;
                }
            }
        });
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = (env, surface);
    }
}

/// Surface resized: recreate the swapchain at the new size.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_hub_util_YuuAvatarNative_nativeSurfaceChanged<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    width: jint,
    height: jint,
) {
    engine::resize_renderer(width, height);
}

/// Surface gone: destroy the renderer, stop the thread. Traits/pose survive.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_hub_util_YuuAvatarNative_nativeSurfaceDestroyed<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
) {
    engine::destroy_renderer();
}

/// Pause or resume drawing (keeps the renderer alive).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_hub_util_YuuAvatarNative_nativeSetPaused<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    paused: jboolean,
) {
    engine::set_paused(paused != jni::sys::JNI_FALSE);
}

/// Pose preset ids as JSON (`["idle","wave",...]`) for the gallery.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_hub_util_YuuAvatarNative_nativeGetPosesJson<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
) -> jstring {
    let json = serde_json::to_string(
        &avatar::preset_poses().iter().map(|p| &p.id).collect::<Vec<_>>(),
    )
    .unwrap_or_else(|_| "[]".to_string());
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        match env.new_string(json) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// Activity gone: destroy everything.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_hub_util_YuuAvatarNative_nativeOnDestroy<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
) {
    engine::destroy_engine();
}

#[cfg(test)]
mod tests {
    use crate::mesh::build_avatar_mesh;

    #[test]
    fn engine_smoke() {
        let mesh = build_avatar_mesh();
        assert!(!mesh.is_empty());
        let poses = crate::avatar::preset_poses();
        assert!(poses.iter().any(|p| p.id == "wave"));
    }
}
