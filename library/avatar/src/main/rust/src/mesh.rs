//! Skinned avatar mesh: vertex format, procedural fallback, glTF loading.
//!
//! The v1 path is procedural: [`build_avatar_mesh`] emits an upper-body
//! approximation (head sphere-ish, torso, arms) bound to the [`crate::avatar`]
//! skeleton with 4-bone weights. [`load_gltf_mesh`] parses a rigged
//! `base_upperbody.glb` when one is bundled, remapping its joints onto the
//! same UBO order so poses behave identically either way.

use crate::avatar::{joint_by_name, ALL_JOINTS, JOINT_COUNT};

/// One skinned vertex. Matches `yuu_avatar.vert` locations 0..4.
#[derive(Clone, Copy, Debug, Default)]
#[repr(C)]
pub struct YuuVertex {
    /// Model-space position.
    pub pos: [f32; 3],
    /// Model-space normal.
    pub normal: [f32; 3],
    /// Albedo UV.
    pub uv: [f32; 2],
    /// Bone indices into the UBO order.
    pub joints: [u32; 4],
    /// Bone weights (normalized).
    pub weights: [f32; 4],
}

/// A rigid part swept into skinned vertices with a single bone weight.
struct Part {
    /// Center of the box.
    center: [f32; 3],
    /// Full size of the box.
    size: [f32; 3],
    /// Primary bone index.
    joint: u32,
    /// Secondary bone index (blends toward the parent at the seam).
    blend_joint: u32,
    /// Blend factor 0..1 toward the secondary bone.
    blend: f32,
    /// Skin-tone albedo flag (true = skin, false = shirt).
    skin: bool,
}

/// Push an axis-aligned box as skinned triangles into `out`.
fn box_skinned(out: &mut Vec<YuuVertex>, part: &Part) {
    let (cx, cy, cz) = (part.center[0], part.center[1], part.center[2]);
    let (sx, sy, sz) = (part.size[0] / 2.0, part.size[1] / 2.0, part.size[2] / 2.0);
    // 8 corners.
    let c = [
        [cx - sx, cy - sy, cz - sz],
        [cx + sx, cy - sy, cz - sz],
        [cx + sx, cy + sy, cz - sz],
        [cx - sx, cy + sy, cz - sz],
        [cx - sx, cy - sy, cz + sz],
        [cx + sx, cy - sy, cz + sz],
        [cx + sx, cy + sy, cz + sz],
        [cx - sx, cy + sy, cz + sz],
    ];
    // 6 faces: (corners, normal).
    let faces: [([usize; 4], [f32; 3]); 6] = [
        ([0, 1, 2, 3], [0.0, 0.0, -1.0]),
        ([5, 4, 7, 6], [0.0, 0.0, 1.0]),
        ([4, 0, 3, 7], [-1.0, 0.0, 0.0]),
        ([1, 5, 6, 2], [1.0, 0.0, 0.0]),
        ([4, 5, 1, 0], [0.0, -1.0, 0.0]),
        ([3, 2, 6, 7], [0.0, 1.0, 0.0]),
    ];
    let w0 = 1.0 - part.blend;
    for (corners, normal) in faces {
        // Simple planar UV per face.
        let uvs = [[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]];
        let idx = [corners[0], corners[1], corners[2], corners[0], corners[2], corners[3]];
        let uv_idx = [0, 1, 2, 0, 2, 3];
        for k in 0..6 {
            out.push(YuuVertex {
                pos: c[idx[k]],
                normal,
                uv: if part.skin { [uvs[uv_idx[k]][0] * 0.25, uvs[uv_idx[k]][1] * 0.25] } else { [0.5 + uvs[uv_idx[k]][0] * 0.5, uvs[uv_idx[k]][1] * 0.5] },
                joints: [part.joint, part.blend_joint, 0, 0],
                weights: [w0, part.blend, 0.0, 0.0],
            });
        }
    }
}

/// Joint index helper by name (0 when unknown — weighted to root).
fn j(name: &str) -> u32 {
    joint_by_name(name).map(|jj| jj as u32).unwrap_or(0)
}

/// Build the procedural fallback upper-body mesh bound to the v1 skeleton.
///
/// Proportions are Mii-like (big head, simple torso/arms); the renderer tints
/// skin vs shirt regions via the UV split in [`box_skinned`].
pub fn build_avatar_mesh() -> Vec<YuuVertex> {
    let mut out = Vec::with_capacity(1024);
    // Head: stacked boxes approximating a rounded skull + jaw.
    let head = Part { center: [0.0, 1.28, 0.0], size: [0.30, 0.30, 0.28], joint: j("head"), blend_joint: j("neck"), blend: 0.15, skin: true };
    box_skinned(&mut out, &head);
    let jaw = Part { center: [0.0, 1.10, 0.02], size: [0.24, 0.12, 0.24], joint: j("head"), blend_joint: j("neck"), blend: 0.25, skin: true };
    box_skinned(&mut out, &jaw);
    // Neck.
    let neck = Part { center: [0.0, 1.02, 0.0], size: [0.10, 0.10, 0.10], joint: j("neck"), blend_joint: j("chest"), blend: 0.3, skin: true };
    box_skinned(&mut out, &neck);
    // Torso (shirt region).
    let chest = Part { center: [0.0, 0.82, 0.0], size: [0.44, 0.30, 0.26], joint: j("chest"), blend_joint: j("spine"), blend: 0.25, skin: false };
    box_skinned(&mut out, &chest);
    let waist = Part { center: [0.0, 0.58, 0.0], size: [0.38, 0.22, 0.24], joint: j("spine"), blend_joint: j("root"), blend: 0.3, skin: false };
    box_skinned(&mut out, &waist);
    // Arms (skin hands, shirt sleeves).
    for side in [-1.0f32, 1.0] {
        let (clav, upper, fore, hand) = if side < 0.0 {
            ("clavicle_L", "upperArm_L", "forearm_L", "hand_L")
        } else {
            ("clavicle_R", "upperArm_R", "forearm_R", "hand_R")
        };
        let shoulder = Part { center: [side * 0.26, 0.90, 0.0], size: [0.12, 0.12, 0.14], joint: j(upper), blend_joint: j(clav), blend: 0.4, skin: false };
        box_skinned(&mut out, &shoulder);
        let upper_arm = Part { center: [side * 0.30, 0.74, 0.0], size: [0.11, 0.26, 0.12], joint: j(upper), blend_joint: j(clav), blend: 0.2, skin: false };
        box_skinned(&mut out, &upper_arm);
        let forearm = Part { center: [side * 0.31, 0.50, 0.0], size: [0.10, 0.24, 0.11], joint: j(fore), blend_joint: j(upper), blend: 0.25, skin: true };
        box_skinned(&mut out, &forearm);
        let hand = Part { center: [side * 0.31, 0.34, 0.0], size: [0.10, 0.10, 0.10], joint: j(hand), blend_joint: j(fore), blend: 0.3, skin: true };
        box_skinned(&mut out, &hand);
    }
    out
}

/// Load a rigged glTF mesh, remapping its joints onto the UBO order.
///
/// Returns the procedural fallback when `bytes` fail to parse, so a missing
/// or corrupt `base_upperbody.glb` never breaks the preview — the skeleton and
/// poses behave identically since both paths share bone order.
pub fn load_gltf_mesh(bytes: &[u8]) -> Vec<YuuVertex> {
    match parse_gltf(bytes) {
        Some(mesh) if !mesh.is_empty() => mesh,
        _ => build_avatar_mesh(),
    }
}

/// Minimal glTF parser: first mesh primitive with POSITION + JOINTS_0/WEIGHTS_0.
fn parse_gltf(bytes: &[u8]) -> Option<Vec<YuuVertex>> {
    let (doc, buffers, _) = gltf::import_slice(bytes).ok()?;
    let mesh = doc.meshes().next()?;
    let prim = mesh.primitives().next()?;
    let reader = prim.reader(|buffer| Some(&buffers[buffer.index()]));
    let positions: Vec<[f32; 3]> = reader.read_positions()?.collect();
    let normals: Vec<[f32; 3]> = reader
        .read_normals()
        .map(|it| it.collect())
        .unwrap_or_else(|| vec![[0.0, 1.0, 0.0]; positions.len()]);
    let uvs: Vec<[f32; 2]> = reader
        .read_tex_coords(0)
        .map(|tc| tc.into_f32().collect())
        .unwrap_or_else(|| vec![[0.0, 0.0]; positions.len()]);
    let joints_raw: Vec<[u16; 4]> = reader.read_joints(0).map(|it| it.into_u16().collect()).unwrap_or_default();
    let weights_raw: Vec<[f32; 4]> = reader.read_weights(0).map(|it| it.into_f32().collect()).unwrap_or_default();
    // Skin-joint-slot -> UBO order remap via node names. JOINTS_0 indexes
    // `skin.joints` (an indirection into nodes), NOT global node order, so
    // resolve through the skin's joint list.
    let mut remap = vec![0u32; 256];
    if let Some(skin) = doc.skins().next() {
        for (slot, node) in skin.joints().enumerate() {
            if slot >= remap.len() {
                break;
            }
            if let Some(name) = node.name() {
                if let Some(jj) = joint_by_name(name) {
                    remap[slot] = jj as u32;
                }
            }
        }
    }
    let mut out = Vec::with_capacity(positions.len());
    for i in 0..positions.len() {
        let js = joints_raw.get(i).copied().unwrap_or([0; 4]);
        let ws = normalize_weights(weights_raw.get(i).copied().unwrap_or([1.0, 0.0, 0.0, 0.0]));
        out.push(YuuVertex {
            pos: positions[i],
            normal: *normals.get(i).unwrap_or(&[0.0, 1.0, 0.0]),
            uv: *uvs.get(i).unwrap_or(&[0.0, 0.0]),
            joints: [
                *remap.get(js[0] as usize).unwrap_or(&0),
                *remap.get(js[1] as usize).unwrap_or(&0),
                *remap.get(js[2] as usize).unwrap_or(&0),
                *remap.get(js[3] as usize).unwrap_or(&0),
            ],
            weights: ws,
        });
    }
    // Indexed or flat: expand indices when present. OOB indices mean a
    // corrupt asset — bail to the procedural fallback instead of clamping
    // (which would smear the last vertex across the mesh).
    if let Some(indices) = reader.read_indices() {
        let idx: Vec<u32> = indices.into_u32().collect();
        if idx.iter().any(|ii| (*ii as usize) >= out.len()) {
            return None;
        }
        let expanded: Vec<YuuVertex> = idx.iter().map(|ii| out[*ii as usize]).collect();
        return Some(expanded);
    }
    Some(out)
}

/// Normalize skin weights (guard degenerate sums); unweighted verts bind
/// fully to joint 0 via the caller's joint default.
fn normalize_weights(ws: [f32; 4]) -> [f32; 4] {
    let sum: f32 = ws.iter().sum();
    if sum < 1e-6 {
        [1.0, 0.0, 0.0, 0.0]
    } else {
        [ws[0] / sum, ws[1] / sum, ws[2] / sum, ws[3] / sum]
    }
}

/// Vertex count sanity for the Vulkan budget check.
pub fn mesh_budgets() -> (usize, usize) {
    // Static avatar mesh cap + one dynamic upload (same buffer reused).
    (65_536, 8_192)
}

/// Joint count exposed for UBO sizing asserts.
pub fn joint_count() -> usize {
    JOINT_COUNT
}

/// All joint names in UBO order (for asset validation logs).
pub fn joint_names() -> Vec<&'static str> {
    ALL_JOINTS.iter().copied().map(crate::avatar::joint_name).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fallback_mesh_is_nonempty_and_weighted() {
        let mesh = build_avatar_mesh();
        assert!(!mesh.is_empty());
        for v in mesh.iter().take(64) {
            let w: f32 = v.weights.iter().sum();
            assert!((w - 1.0).abs() < 1e-4, "weights must sum to 1, got {w}");
        }
    }

    #[test]
    fn corrupt_gltf_falls_back() {
        let mesh = load_gltf_mesh(b"not a glb");
        assert!(!mesh.is_empty());
    }
}
