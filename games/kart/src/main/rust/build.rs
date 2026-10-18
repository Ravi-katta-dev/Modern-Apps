use std::path::PathBuf;
use std::process::Command;

/// Compile the kart shaders to SPIR-V with glslc when available.
///
/// Same arrangement as games/voxels: `include_bytes!` in vulkan/pipeline.rs
/// requires the `.spv` files to exist even when no glslc is installed, so a
/// minimal valid module is written as a fallback (it draws nothing useful, but
/// the crate still builds and the CPU-side race sim still ticks).
fn main() {
    let shaders_dir = PathBuf::from("shaders");
    let pairs = [("kart.vert", "kart.vert.spv"), ("kart.frag", "kart.frag.spv")];

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
/// PATH. The Windows path is read from the same SDK the Gradle build uses.
fn find_glslc() -> Option<PathBuf> {
    let mut candidates: Vec<PathBuf> = Vec::new();
    let sdk = std::env::var("ANDROID_HOME")
        .or_else(|_| std::env::var("ANDROID_SDK_ROOT"))
        .ok();
    if let Some(sdk) = sdk {
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
    let probe = if cfg!(windows) { "where" } else { "which" };
    if Command::new(probe)
        .arg("glslc")
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false)
    {
        return Some(PathBuf::from("glslc"));
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
