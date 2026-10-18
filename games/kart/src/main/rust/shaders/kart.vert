#version 450
// Unlit vertex-color kart shader: the mesh carries the art (asphalt gray,
// grass green, per-kart box colors), so no textures and no samplers.
layout(location = 0) in vec3 inPos;
layout(location = 1) in vec3 inColor;

layout(location = 0) out vec3 vColor;
layout(location = 1) out vec3 vWorld;

layout(set = 0, binding = 0) uniform Ubo {
    mat4 viewProj;
    vec3 sunDir;
    vec3 camPos;
} ubo;

void main() {
    vColor = inColor;
    vWorld = inPos;
    vec4 clip = ubo.viewProj * vec4(inPos, 1.0);
    // Vulkan Y flip: NDC Y points down, and the flip inverts winding (hence
    // CULL_NONE in the pipeline) — same arrangement as games/voxels.
    clip.y = -clip.y;
    gl_Position = clip;
}
