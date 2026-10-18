#version 300 es
// Unlit vertex-color kart shader (GLES 3.0 port of kart.vert): the mesh
// carries the art (asphalt gray, grass green, per-kart box colors), so no
// textures and no samplers. The Vulkan `clip.y` flip is removed — OpenGL NDC
// Y already points up — so winding stays as authored (cull off either way).
layout(location = 0) in vec3 inPos;
layout(location = 1) in vec3 inColor;

out vec3 vColor;
out vec3 vWorld;

uniform mat4 uViewProj;

void main() {
    vColor = inColor;
    vWorld = inPos;
    gl_Position = uViewProj * vec4(inPos, 1.0);
}
