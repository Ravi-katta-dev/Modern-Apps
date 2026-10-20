#version 300 es
// Skinned Yuu avatar vertex shader (GLES 3.0 port of yuu_avatar.vert):
// 4-bone linear-blend skinning against the uBones array, then the shared
// view-projection. The Vulkan `clip.y` flip is removed — OpenGL NDC Y already
// points up — so winding stays as authored (cull off either way).
// NOTE(Mali): joints are vec4 (see yuu_avatar.vert) — integer attributes
// crash Mali drivers.
layout(location = 0) in vec3 inPos;
layout(location = 1) in vec3 inNormal;
layout(location = 2) in vec2 inUv;
layout(location = 3) in vec4 inJoints;
layout(location = 4) in vec4 inWeights;

out vec3 vNormal;
out vec2 vUv;
out vec3 vWorld;

uniform mat4 uViewProj;
uniform mat4 uBones[32];

void main() {
    mat4 skin =
        inWeights.x * uBones[int(inJoints.x)] +
        inWeights.y * uBones[int(inJoints.y)] +
        inWeights.z * uBones[int(inJoints.z)] +
        inWeights.w * uBones[int(inJoints.w)];
    vec4 world = skin * vec4(inPos, 1.0);
    vWorld = world.xyz;
    vNormal = normalize(mat3(skin) * inNormal);
    vUv = inUv;
    gl_Position = uViewProj * world;
}
