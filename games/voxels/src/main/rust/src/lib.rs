pub mod world;
pub mod blessing;
pub mod container;
pub mod entity;
pub mod item;
pub mod player;
pub mod raycast;
pub mod input;
pub mod inventory;
pub mod villager;
pub mod ambience;
pub mod fishing;
pub mod engine;
pub mod net;
pub mod texture_atlas;
pub mod vulkan;

use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jfloat, jint, jstring};
use jni::{Env, EnvUnowned};

#[cfg(target_os = "android")]
use std::os::raw::c_void as CVoid;

/// Decodes a JNI string argument into a Rust `String`, returning `None` if
/// the Java string cannot be read.
fn get_string(env: &Env, s: &JString) -> Option<String> {
    s.try_to_string(env).ok()
}

include!("lib_jni_lifecycle.rs");
include!("lib_jni_input.rs");
include!("lib_jni_inventory.rs");
include!("lib_jni_query.rs");
include!("lib_jni_net.rs");

#[cfg(test)]
mod tests {
    use crate::world::chunk::{Chunk, ChunkPos};
    use crate::world::mesher::mesh_chunk;
    #[test]
    fn engine_smoke() {
        use crate::world::generator::TerrainGen;
        let gen = TerrainGen::new(42);
        let h = gen.height_at(0.0, 0.0);
        assert!(h > 0);
        let mut c = Chunk::new(ChunkPos(0, 0));
        gen.fill_chunk(&mut c);
        assert!(c.generated);
        let mesh = mesh_chunk(&c, &|_, _, _| (0, 0), &|_, _| [0.4, 0.7, 0.3]);
        let total: usize = mesh.iter().filter_map(|o| o.as_ref()).map(|m| m.vertices.len()).sum();
        assert!(total > 0 || c.sections.iter().all(|s| s.is_none() || s.as_ref().unwrap().is_empty()));
    }
}
