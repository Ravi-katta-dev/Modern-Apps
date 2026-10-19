# models/

Place the authored `base_upperbody.glb` here. Requirements (see `../../AGENTS.md`
"Base mesh asset"):

- ~13–15 joints named per `joint_name()` (`root/spine/chest/neck/head`,
  `clavicle_L/R`, `upperArm_L/R`, `forearm_L/R`, `hand_L/R`)
- `JOINTS_0` / `WEIGHTS_0` 4-weight skinning on the first mesh primitive
- Distinct `glasses` / `beard` / hair nodes for attachments
- 2K albedo honoring the skin `[0,.25]` / shirt `[.5,1]` UV split
- ≤65536 verts (tighter cap for grid cells)

The `.glb` ships uncompressed (`noCompress` in `build.gradle.kts`) and is
handed to native as a detached fd by `YuuAvatarAssets`. Until it lands, the
engine renders the procedural fallback mesh.
