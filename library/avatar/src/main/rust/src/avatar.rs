//! Yuu avatar model: joint hierarchy, trait mapping, preset poses.
//!
//! The skeleton is the v1 upper-body rig: `root -> spine -> chest -> neck ->
//! head`, plus `clavicle_L/R -> upperArm -> forearm -> hand` on each side
//! (~13 joints, no hips/legs/feet). Joint order here is the bone-matrix UBO
//! order, so it must match the skinning shader's `bones[]` indexing.
//!
//! Trait mapping from `YuuFaceConfig` JSON is coarse by design (see the plan):
//! skin tone tints the material, body height/weight scale bones, face shape
//! scales the head, style indices pick attachment/texture variants, and
//! mole/wrinkle/blush are v1-skipped.

use glam::{Mat4, Quat, Vec3};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

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

/// Serializable joint rotation (Euler degrees, XYZ order) for one joint.
#[derive(Clone, Copy, Debug, Default, Serialize, Deserialize)]
pub struct JointRotation {
    /// Pitch degrees.
    #[serde(default)]
    pub x: f32,
    /// Yaw degrees.
    #[serde(default)]
    pub y: f32,
    /// Roll degrees.
    #[serde(default)]
    pub z: f32,
}

impl JointRotation {
    /// Identity rotation.
    pub const fn identity() -> Self {
        Self { x: 0.0, y: 0.0, z: 0.0 }
    }

    /// Convert to a quaternion.
    pub fn to_quat(self) -> Quat {
        Quat::from_euler(
            glam::EulerRot::XYZ,
            self.x.to_radians(),
            self.y.to_radians(),
            self.z.to_radians(),
        )
    }
}

/// A named preset pose: per-joint rotations over the rest pose.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct YuuPose {
    /// Stable id (matches the Kotlin `poseId`).
    pub id: String,
    /// Display name.
    pub name: String,
    /// Joint rotations; joints absent from the map stay at rest.
    #[serde(default)]
    pub joints: HashMap<String, JointRotation>,
}

/// Joint name key used in [`YuuPose::joints`].
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

/// Preset gallery ids, in gallery order.
pub const POSE_IDLE: &str = "idle";
/// Friendly wave with the right hand.
pub const POSE_WAVE: &str = "wave";
/// Right-hand thumbs-up.
pub const POSE_THUMBS_UP: &str = "thumbs_up";
/// Chin resting on the right hand.
pub const POSE_THINKING: &str = "thinking";
/// Both arms raised in celebration.
pub const POSE_CHEERING: &str = "cheering";

/// Build the v1 preset gallery.
pub fn preset_poses() -> Vec<YuuPose> {
    vec![
        YuuPose { id: POSE_IDLE.into(), name: "Idle".into(), joints: HashMap::new() },
        YuuPose {
            id: POSE_WAVE.into(),
            name: "Wave".into(),
            joints: HashMap::from([
                ("upperArm_R".into(), JointRotation { x: 0.0, y: 0.0, z: -140.0 }),
                ("forearm_R".into(), JointRotation { x: 0.0, y: 0.0, z: -20.0 }),
                ("hand_R".into(), JointRotation { x: 0.0, y: 25.0, z: 0.0 }),
                ("head".into(), JointRotation { x: 0.0, y: -8.0, z: 4.0 }),
            ]),
        },
        YuuPose {
            id: POSE_THUMBS_UP.into(),
            name: "Thumbs up".into(),
            joints: HashMap::from([
                ("upperArm_R".into(), JointRotation { x: -50.0, y: 0.0, z: -30.0 }),
                ("forearm_R".into(), JointRotation { x: -40.0, y: 0.0, z: 0.0 }),
                ("hand_R".into(), JointRotation { x: -10.0, y: 0.0, z: 90.0 }),
                ("head".into(), JointRotation { x: -4.0, y: 0.0, z: 0.0 }),
            ]),
        },
        YuuPose {
            id: POSE_THINKING.into(),
            name: "Thinking".into(),
            joints: HashMap::from([
                ("upperArm_R".into(), JointRotation { x: -30.0, y: 0.0, z: -45.0 }),
                ("forearm_R".into(), JointRotation { x: -95.0, y: 0.0, z: 15.0 }),
                ("hand_R".into(), JointRotation { x: -20.0, y: 0.0, z: 0.0 }),
                ("head".into(), JointRotation { x: 8.0, y: 12.0, z: -6.0 }),
            ]),
        },
        YuuPose {
            id: POSE_CHEERING.into(),
            name: "Cheering".into(),
            joints: HashMap::from([
                ("upperArm_L".into(), JointRotation { x: 0.0, y: 0.0, z: 140.0 }),
                ("forearm_L".into(), JointRotation { x: 0.0, y: 0.0, z: 20.0 }),
                ("upperArm_R".into(), JointRotation { x: 0.0, y: 0.0, z: -140.0 }),
                ("forearm_R".into(), JointRotation { x: 0.0, y: 0.0, z: -20.0 }),
                ("head".into(), JointRotation { x: -10.0, y: 0.0, z: 0.0 }),
            ]),
        },
    ]
}

/// Find a preset by id.
pub fn preset_by_id(id: &str) -> Option<YuuPose> {
    preset_poses().into_iter().find(|p| p.id == id)
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
/// `MAX_BONES` matrices; unused slots are identity.
pub fn compute_bone_matrices(traits: &YuuTraits, pose: &YuuPose, time_s: f32) -> [Mat4; MAX_BONES] {
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
        out[joint as usize] = parent_world * local;
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

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
    fn traits_parse_leniently() {
        let t = YuuTraits::from_config_json("{}");
        assert_eq!(t.body_height, 0.5);
        let bad = YuuTraits::from_config_json("not json");
        assert_eq!(bad.body_height, 0.5);
    }
}
