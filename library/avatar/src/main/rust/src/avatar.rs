//! Yuu avatar model: joint hierarchy, trait mapping, forward kinematics.
//!
//! The skeleton is the v1 upper-body rig: `root -> spine -> chest -> neck ->
//! head`, plus `clavicle_L/R -> upperArm -> forearm -> hand` on each side
//! (~13 joints, no hips/legs/feet). Joint order here is the bone-matrix UBO
//! order, so it must match the skinning shader's `bones[]` indexing.
//!
//! Poses live in [`crate::pose`]. Trait mapping from `YuuFaceConfig` JSON is
//! coarse by design (see the plan): skin tone tints the material, body
//! height/weight scale bones, face shape scales the head, style indices pick
//! attachment/texture variants, and mole/wrinkle/blush are v1-skipped.

use crate::pose::{JointRotation, YuuPose};
use glam::{Mat4, Quat, Vec3};
use serde::{Deserialize, Serialize};

/// Bone-matrix UBO capacity: 13 v1 joints fit with headroom for attachments.
pub const MAX_BONES: usize = 32;

/// Joint indices in UBO order.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[repr(usize)]
pub enum Joint {
    /// Scene root (identity base for the turntable sway).
    Root = 0,
    /// Lower torso; body height scales this chain.
    Spine = 1,
    /// Upper torso; body weight widens this chain.
    Chest = 2,
    /// Head stalk; face shape scales the head above it.
    Neck = 3,
    /// Face + hair + attachments anchor.
    Head = 4,
    /// Left collar anchor.
    ClavicleL = 5,
    /// Left upper arm.
    UpperArmL = 6,
    /// Left forearm.
    ForearmL = 7,
    /// Left hand (wave/thumbs-up end effector).
    HandL = 8,
    /// Right collar anchor.
    ClavicleR = 9,
    /// Right upper arm.
    UpperArmR = 10,
    /// Right forearm.
    ForearmR = 11,
    /// Right hand (wave/thumbs-up end effector).
    HandR = 12,
}

/// Number of modeled joints (indices `0..JOINT_COUNT`).
pub const JOINT_COUNT: usize = 13;

/// Rest-pose local offsets (metres) from each joint's parent.
pub fn rest_offset(joint: Joint) -> Vec3 {
    match joint {
        Joint::Root => Vec3::ZERO,
        Joint::Spine => Vec3::new(0.0, 0.55, 0.0),
        Joint::Chest => Vec3::new(0.0, 0.28, 0.0),
        Joint::Neck => Vec3::new(0.0, 0.22, 0.0),
        Joint::Head => Vec3::new(0.0, 0.14, 0.0),
        Joint::ClavicleL => Vec3::new(-0.16, 0.16, 0.0),
        Joint::UpperArmL => Vec3::new(-0.10, -0.04, 0.0),
        Joint::ForearmL => Vec3::new(0.0, -0.26, 0.0),
        Joint::HandL => Vec3::new(0.0, -0.24, 0.0),
        Joint::ClavicleR => Vec3::new(0.16, 0.16, 0.0),
        Joint::UpperArmR => Vec3::new(0.10, -0.04, 0.0),
        Joint::ForearmR => Vec3::new(0.0, -0.26, 0.0),
        Joint::HandR => Vec3::new(0.0, -0.24, 0.0),
    }
}

/// Parent joint index, if any.
pub fn parent_of(joint: Joint) -> Option<Joint> {
    match joint {
        Joint::Root => None,
        Joint::Spine => Some(Joint::Root),
        Joint::Chest => Some(Joint::Spine),
        Joint::Neck => Some(Joint::Chest),
        Joint::Head => Some(Joint::Neck),
        Joint::ClavicleL => Some(Joint::Chest),
        Joint::UpperArmL => Some(Joint::ClavicleL),
        Joint::ForearmL => Some(Joint::UpperArmL),
        Joint::HandL => Some(Joint::ForearmL),
        Joint::ClavicleR => Some(Joint::Chest),
        Joint::UpperArmR => Some(Joint::ClavicleR),
        Joint::ForearmR => Some(Joint::UpperArmR),
        Joint::HandR => Some(Joint::ForearmR),
    }
}

/// Children of a joint, in hierarchy order.
pub fn children_of(joint: Joint) -> &'static [Joint] {
    match joint {
        Joint::Root => &[Joint::Spine],
        Joint::Spine => &[Joint::Chest],
        Joint::Chest => &[Joint::Neck, Joint::ClavicleL, Joint::ClavicleR],
        Joint::Neck => &[Joint::Head],
        Joint::Head => &[],
        Joint::ClavicleL => &[Joint::UpperArmL],
        Joint::UpperArmL => &[Joint::ForearmL],
        Joint::ForearmL => &[Joint::HandL],
        Joint::HandL => &[],
        Joint::ClavicleR => &[Joint::UpperArmR],
        Joint::UpperArmR => &[Joint::ForearmR],
        Joint::ForearmR => &[Joint::HandR],
        Joint::HandR => &[],
    }
}

/// All joints in parent-before-child order (safe for forward kinematics).
pub const ALL_JOINTS: [Joint; JOINT_COUNT] = [
    Joint::Root,
    Joint::Spine,
    Joint::Chest,
    Joint::Neck,
    Joint::Head,
    Joint::ClavicleL,
    Joint::UpperArmL,
    Joint::ForearmL,
    Joint::HandL,
    Joint::ClavicleR,
    Joint::UpperArmR,
    Joint::ForearmR,
    Joint::HandR,
];

/// Joint name key used in pose joint maps.
pub fn joint_name(joint: Joint) -> &'static str {
    match joint {
        Joint::Root => "root",
        Joint::Spine => "spine",
        Joint::Chest => "chest",
        Joint::Neck => "neck",
        Joint::Head => "head",
        Joint::ClavicleL => "clavicle_L",
        Joint::UpperArmL => "upperArm_L",
        Joint::ForearmL => "forearm_L",
        Joint::HandL => "hand_L",
        Joint::ClavicleR => "clavicle_R",
        Joint::UpperArmR => "upperArm_R",
        Joint::ForearmR => "forearm_R",
        Joint::HandR => "hand_R",
    }
}

/// Look up a joint by its [`joint_name`] key.
pub fn joint_by_name(name: &str) -> Option<Joint> {
    ALL_JOINTS.iter().copied().find(|j| joint_name(*j) == name)
}

/// Coarse trait mapping from `YuuFaceConfig` JSON.
///
/// Only the fields that affect the 3D body are read here; face-detail fields
/// (eyes/brows/nose/mouth styles, glasses, beard, mole, wrinkles, blush) map
/// to texture regions or attachments in a later phase and are ignored for now.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct YuuTraits {
    /// ARGB long from the fixed skin palette.
    #[serde(default)]
    pub skin_tone: u64,
    /// Normalized body height 0..1.
    #[serde(default = "half")]
    pub body_height: f32,
    /// Normalized body weight 0..1.
    #[serde(default = "half")]
    pub body_weight: f32,
    /// Face shape index 0..7 (drives head scale approximation).
    #[serde(default)]
    pub face_shape: i32,
    /// Hair style index (drives hair attachment variant).
    #[serde(default)]
    pub hair_style: i32,
    /// ARGB long from the fixed hair palette.
    #[serde(default)]
    pub hair_color: u64,
}

fn half() -> f32 {
    0.5
}

impl Default for YuuTraits {
    fn default() -> Self {
        Self {
            skin_tone: 0,
            body_height: 0.5,
            body_weight: 0.5,
            face_shape: 0,
            hair_style: 0,
            hair_color: 0,
        }
    }
}

impl YuuTraits {
    /// Parse from the `YuuFaceConfig` JSON string; unknown/missing fields fall
    /// back to defaults (mirrors the Kotlin `ignoreUnknownKeys` behavior).
    pub fn from_config_json(json: &str) -> Self {
        serde_json::from_str(json).unwrap_or_default()
    }

    /// Skin albedo as linear-ish RGB 0..1 (sRGB bytes unpacked, no transfer).
    pub fn skin_rgb(&self) -> [f32; 3] {
        argb_to_rgb(self.skin_tone, [0.91, 0.71, 0.54])
    }

    /// Hair albedo as RGB 0..1.
    pub fn hair_rgb(&self) -> [f32; 3] {
        argb_to_rgb(self.hair_color, [0.17, 0.17, 0.17])
    }

    /// Spine-chain height scale from `body_height`.
    pub fn height_scale(&self) -> f32 {
        0.9 + self.body_height.clamp(0.0, 1.0) * 0.2
    }

    /// Chest/arm width scale from `body_weight`.
    pub fn weight_scale(&self) -> f32 {
        0.85 + self.body_weight.clamp(0.0, 1.0) * 0.3
    }

    /// Head non-uniform scale approximation from `face_shape`.
    pub fn head_scale(&self) -> Vec3 {
        match self.face_shape.rem_euclid(8) {
            1 => Vec3::new(0.95, 1.0, 0.95),
            2 => Vec3::new(1.08, 0.96, 0.96),
            3 => Vec3::new(1.05, 1.0, 1.0),
            4 => Vec3::new(0.92, 1.08, 0.94),
            5 => Vec3::new(1.1, 1.0, 1.0),
            6 => Vec3::new(1.02, 0.98, 1.0),
            7 => Vec3::new(1.12, 0.94, 1.0),
            _ => Vec3::ONE,
        }
    }
}

/// Unpack an ARGB long to RGB floats, with a fallback.
fn argb_to_rgb(argb: u64, fallback: [f32; 3]) -> [f32; 3] {
    if argb == 0 {
        return fallback;
    }
    let r = ((argb >> 16) & 0xFF) as f32 / 255.0;
    let g = ((argb >> 8) & 0xFF) as f32 / 255.0;
    let b = (argb & 0xFF) as f32 / 255.0;
    [r, g, b]
}

/// Compute world-space bone matrices for a pose over the rest skeleton.
///
/// Applies trait scales (height on spine/chest, weight on chest/arms, head
/// scale on head), then forward kinematics through [`ALL_JOINTS`]. Output is
/// `MAX_BONES` skinning matrices (`world * inverse_bind`): mesh vertices are
/// authored in model space, so each bone must first un-apply its rest pose
/// before applying the posed transform — without the inverse bind, parts
/// render scattered/exploded (double-transformed).
pub fn compute_bone_matrices(traits: &YuuTraits, pose: &YuuPose, time_s: f32) -> [Mat4; MAX_BONES] {
    let inverse_bind = rest_world_matrices();
    let mut out = [Mat4::IDENTITY; MAX_BONES];
    // Gentle idle sway on the root so heroes never look frozen.
    let sway = (time_s * 1.2).sin() * 0.02;
    // World matrices keyed by UBO joint index (= discriminant today; the
    // lookup below keeps parenting correct even if the enum is reordered).
    let mut world = [Mat4::IDENTITY; JOINT_COUNT];
    for joint in ALL_JOINTS.iter().copied() {
        let mut offset = rest_offset(joint);
        // Trait scales.
        match joint {
            Joint::Spine | Joint::Chest => {
                offset.y *= traits.height_scale();
            }
            _ => {}
        }
        match joint {
            Joint::Chest | Joint::ClavicleL | Joint::ClavicleR | Joint::UpperArmL | Joint::UpperArmR => {
                offset.x *= traits.weight_scale();
            }
            _ => {}
        }
        let mut rot = pose
            .joints
            .get(joint_name(joint))
            .copied()
            .unwrap_or_else(JointRotation::identity)
            .to_quat();
        if joint == Joint::Root {
            rot *= Quat::from_rotation_y(sway);
        }
        let mut scale = Vec3::ONE;
        if joint == Joint::Head {
            scale = traits.head_scale();
        }
        let local = Mat4::from_translation(offset) * Mat4::from_quat(rot) * Mat4::from_scale(scale);
        // Parent lookup by ALL_JOINTS position (not discriminant) so a future
        // enum reorder/insert can't silently break parenting.
        let parent_world = match parent_of(joint) {
            Some(p) => {
                let pi = ALL_JOINTS.iter().position(|jj| *jj == p).unwrap_or(usize::MAX);
                if pi == usize::MAX {
                    Mat4::IDENTITY
                } else {
                    world[pi]
                }
            }
            None => Mat4::IDENTITY,
        };
        let ji = ALL_JOINTS.iter().position(|jj| *jj == joint).unwrap_or(usize::MAX);
        if ji != usize::MAX {
            world[ji] = parent_world * local;
        }
        // Skinning matrix: un-apply the rest pose, then apply the posed world
        // transform. Vertices are authored in model space.
        out[joint as usize] = parent_world * local * inverse_bind[joint as usize];
    }
    out
}

/// Inverse bind-pose matrices: inverse of each joint's rest-pose world matrix
/// (identity rotations, unit scales, unscaled offsets). Precomputed per call
/// (13 joints — negligible) so `compute_bone_matrices` stays pure.
fn rest_world_matrices() -> [Mat4; MAX_BONES] {
    let mut world = [Mat4::IDENTITY; JOINT_COUNT];
    for joint in ALL_JOINTS.iter().copied() {
        let local = Mat4::from_translation(rest_offset(joint));
        let parent_world = match parent_of(joint) {
            Some(p) => {
                let pi = ALL_JOINTS.iter().position(|jj| *jj == p).unwrap_or(usize::MAX);
                if pi == usize::MAX {
                    Mat4::IDENTITY
                } else {
                    world[pi]
                }
            }
            None => Mat4::IDENTITY,
        };
        let ji = ALL_JOINTS.iter().position(|jj| *jj == joint).unwrap_or(usize::MAX);
        if ji != usize::MAX {
            world[ji] = parent_world * local;
        }
    }
    let mut out = [Mat4::IDENTITY; MAX_BONES];
    for joint in ALL_JOINTS.iter().copied() {
        let ji = ALL_JOINTS.iter().position(|jj| *jj == joint).unwrap_or(usize::MAX);
        if ji != usize::MAX {
            out[joint as usize] = world[ji].inverse();
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pose::YuuPose;
    use std::collections::HashMap;

    #[test]
    fn skeleton_order_is_topological() {
        for (i, joint) in ALL_JOINTS.iter().copied().enumerate() {
            if let Some(p) = parent_of(joint) {
                let pi = ALL_JOINTS.iter().position(|j| *j == p).unwrap();
                assert!(pi < i);
            }
        }
    }

    #[test]
    fn idle_pose_is_identity_at_origin() {
        let traits = YuuTraits::default();
        let pose = YuuPose { id: "idle".into(), name: "Idle".into(), joints: HashMap::new() };
        let bones = compute_bone_matrices(&traits, &pose, 0.0);
        // Root stays at origin (sway is ~0 at t=0).
        let t = bones[Joint::Root as usize].transform_point3(glam::Vec3::ZERO);
        assert!(t.length() < 1e-5);
    }

    #[test]
    fn idle_bones_hold_model_space_points() {
        // Regression: bone matrices must include the inverse bind pose, so an
        // idle skeleton leaves model-space vertices where authored. (Without
        // it, every part renders double-transformed — scattered/exploded.)
        let traits = YuuTraits::default();
        let pose = YuuPose { id: "idle".into(), name: "Idle".into(), joints: HashMap::new() };
        let bones = compute_bone_matrices(&traits, &pose, 0.0);
        // Head-center vertex (bound fully to head) must stay at its authored
        // position under the idle pose.
        let head_m = bones[Joint::Head as usize];
        let p = head_m.transform_point3(glam::Vec3::new(0.0, 1.28, 0.0));
        assert!((p - glam::Vec3::new(0.0, 1.28, 0.0)).length() < 1e-3, "head drifted to {p:?}");
        // Hand-center vertex (bound fully to hand) likewise.
        let hand_m = bones[Joint::HandR as usize];
        let q = hand_m.transform_point3(glam::Vec3::new(0.31, 0.34, 0.0));
        assert!((q - glam::Vec3::new(0.31, 0.34, 0.0)).length() < 1e-3, "hand drifted to {q:?}");
    }

    #[test]
    fn traits_parse_leniently() {
        let t = YuuTraits::from_config_json("{}");
        assert_eq!(t.body_height, 0.5);
        let bad = YuuTraits::from_config_json("not json");
        assert_eq!(bad.body_height, 0.5);
    }
}

