package com.vayunmathur.games.hub.ui.yuu

import kotlinx.serialization.Serializable

/**
 * A named preset 3D pose: per-joint rotations over the rest skeleton.
 * Mirrors the Rust `YuuPose` in `src/main/rust/src/avatar.rs` — the engine
 * owns the authoritative preset list; this is the Kotlin gallery view of it.
 */
@Serializable
data class YuuPose(
    val id: String,
    val name: String,
)

/** The v1 preset gallery, in gallery order. Must match Rust `preset_poses`. */
val YUU_POSES = listOf(
    YuuPose("idle", "Idle"),
    YuuPose("wave", "Wave"),
    YuuPose("thumbs_up", "Thumbs up"),
    YuuPose("thinking", "Thinking"),
    YuuPose("cheering", "Cheering"),
)

/** Fallback when a stored pose id is unknown. */
const val YUU_POSE_DEFAULT = "idle"
