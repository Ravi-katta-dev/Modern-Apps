use std::path::PathBuf;
use std::process::Command;

/// Compile the Yuu avatar shaders to SPIR-V with glslc when available.
///
/// Same arrangement as games/voxels and games/kart: `include_bytes!` in
/// vulkan/pipeline.rs requires the `.spv` files to exist even when no glslc is
/// installed, so a minimal valid module is written as a fallback (it draws
/// nothing useful, but the crate still builds).
fn main() {
    let shaders_dir = PathBuf::from("shaders");
    let pairs = [("yuu_avatar.vert", "yuu_avatar.vert.spv"), ("yuu_avatar.frag", "yuu_avatar.frag.spv")];

    if let Some(glslc) = find_glslc() {
        println!("cargo:warning=Found glslc at {}", glslc.display());
        for (src, spv) in &pairs {
            let _ = Command::new(&glslc)
                .arg("-o")
                .arg(shaders_dir.join(spv))
                .arg(shaders_dir.join(src))
                .status();
        }
    }

    for (src, spv) in &pairs {
        let out = shaders_dir.join(spv);
        if !out.exists() {
            let bytes = if src.ends_with(".vert") {
                minimal_vert_spv()
            } else {
                minimal_frag_spv()
            };
            std::fs::write(&out, bytes).ok();
        }
        println!("cargo:rerun-if-changed=shaders/{src}");
    }
    println!("cargo:rerun-if-changed=build.rs");
}

/// glslc ships with the NDK (shader-tools) on every host OS; fall back to
/// PATH. Probes SDK-derived NDK locations and PATH first; a pinned
/// machine-specific path is the last resort so other hosts/CI don't silently
/// fall to the minimal-SPIRV fallback.
fn find_glslc() -> Option<PathBuf> {
    // PATH first: hermetic toolchains (CI) win over host SDK probing.
    let probe = if cfg!(windows) { "where" } else { "which" };
    if Command::new(probe)
        .arg("glslc")
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false)
    {
        return Some(PathBuf::from("glslc"));
    }
    let mut candidates: Vec<PathBuf> = Vec::new();
    let sdk = std::env::var("ANDROID_HOME")
        .or_else(|_| std::env::var("ANDROID_SDK_ROOT"))
        .ok();
    if let Some(sdk) = sdk {
        // Prefer the newest installed NDK over the pinned revision.
        if let Ok(ndk_dir) = std::fs::read_dir(PathBuf::from(&sdk).join("ndk")) {
            let mut versions: Vec<_> = ndk_dir.filter_map(|e| e.ok().map(|e| e.path())).collect();
            versions.sort();
            for ndk in versions.iter().rev() {
                for tag in ["windows-x86_64", "linux-x86_64", "darwin-x86_64"] {
                    candidates.push(
                        ndk.join("shader-tools").join(tag).join(if tag.starts_with("windows") {
                            "glslc.exe"
                        } else {
                            "glslc"
                        }),
                    );
                }
            }
        }
        // Pinned fallback (kept for hosts with only this NDK).
        for tag in ["windows-x86_64", "linux-x86_64", "darwin-x86_64"] {
            candidates.push(
                PathBuf::from(&sdk)
                    .join("ndk")
                    .join("29.0.14206865")
                    .join("shader-tools")
                    .join(tag)
                    .join(if tag.starts_with("windows") {
                        "glslc.exe"
                    } else {
                        "glslc"
                    }),
            );
        }
    }
    for mac_linux in [
        "/Users/vayun/devmate-android-toolchain/android-sdk/ndk/29.0.14206865/shader-tools/darwin-x86_64/glslc",
        "/Users/vayun/devmate-android-toolchain/android-sdk/ndk/29.0.14206865/shader-tools/linux-x86_64/glslc",
    ] {
        candidates.push(PathBuf::from(mac_linux));
    }
    for c in candidates {
        if c.exists() {
            return Some(c);
        }
    }
    None
}

fn minimal_vert_spv() -> Vec<u8> {
    vec![
        0x03, 0x02, 0x23, 0x07, 0x00, 0x00, 0x01, 0x00, 0x0a, 0x00, 0x08, 0x00,
        0x28, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x11, 0x00, 0x02, 0x00,
        0x01, 0x00, 0x00, 0x00,
    ]
}

fn minimal_frag_spv() -> Vec<u8> {
    vec![
        0x03, 0x02, 0x23, 0x07, 0x00, 0x00, 0x01, 0x00, 0x0a, 0x00, 0x08, 0x00,
        0x1c, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x11, 0x00, 0x02, 0x00,
        0x01, 0x00, 0x00, 0x00,
    ]
}
