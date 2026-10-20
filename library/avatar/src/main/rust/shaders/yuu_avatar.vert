#version 450
// Skinned Yuu avatar vertex shader: 4-bone linear-blend skinning against the
// bone-matrix UBO, then the shared view-projection. Matches YuuVkVertex
// locations 0..4 in vulkan/buffers.rs.
//
// NOTE(Mali): joints arrive as vec4, NOT uvec4 — the Mali SPIR-V frontend
// SIGSEGVs parsing integer vertex inputs (fault inside vkCreateGraphics-
// Pipelines, uncatchable). int() conversion at index time is exact for our
// small joint ids.
layout(location = 0) in vec3 inPos;
layout(location = 1) in vec3 inNormal;
layout(location = 2) in vec2 inUv;
layout(location = 3) in vec4 inJoints;
layout(location = 4) in vec4 inWeights;

layout(location = 0) out vec3 vNormal;
layout(location = 1) out vec2 vUv;
layout(location = 2) out vec3 vWorld;

layout(set = 0, binding = 0) uniform Ubo {
    mat4 viewProj;
    vec3 sunDir;
    vec3 camPos;
    mat4 bones[32];
} ubo;

void main() {
    mat4 skin =
        inWeights.x * ubo.bones[int(inJoints.x)] +
        inWeights.y * ubo.bones[int(inJoints.y)] +
        inWeights.z * ubo.bones[int(inJoints.z)] +
        inWeights.w * ubo.bones[int(inJoints.w)];
    vec4 world = skin * vec4(inPos, 1.0);
    vWorld = world.xyz;
    vNormal = normalize(mat3(skin) * inNormal);
    vUv = inUv;
    vec4 clip = ubo.viewProj * world;
    // Vulkan Y flip: NDC Y points down, and the flip inverts winding (hence
    // CULL_NONE in the pipeline) — same arrangement as games/voxels and kart.
    clip.y = -clip.y;
    gl_Position = clip;
}
