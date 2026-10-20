//! Preset 3D poses: joint rotations over the rest skeleton.
//!
//! Split from `avatar.rs` along module lines (repo `rustFileLength` gate).
//! The engine owns the authoritative preset list; the Kotlin gallery mirrors
//! it (`YUU_POSES` — update both).

use glam::Quat;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

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
