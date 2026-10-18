#version 450
// Flat shading plus distance fog: one fixed sun direction, no shadow maps.
// Karts read as solid boxes against the track; the fog melts the far side of
// the oval into the sky clear color so the pop-in line never shows.
layout(location = 0) in vec3 vColor;
layout(location = 1) in vec3 vWorld;

layout(location = 0) out vec4 outColor;

layout(set = 0, binding = 0) uniform Ubo {
    mat4 viewProj;
    vec3 sunDir;
    vec3 camPos;
} ubo;

const vec3 SKY = vec3(0.45, 0.68, 0.94);

void main() {
    vec3 up = vec3(0.0, 1.0, 0.0);
    float ndl = 0.72 + 0.28 * clamp(dot(up, normalize(ubo.sunDir)), 0.0, 1.0);
    vec3 col = vColor * ndl;
    float dist = length(vWorld - ubo.camPos);
    float fog = 1.0 - exp(-dist * 0.0045);
    outColor = vec4(mix(col, SKY, clamp(fog, 0.0, 1.0)), 1.0);
}
