#version 450
// Textured Yuu avatar fragment shader: albedo sample, single fixed sun plus a
// soft fill so faces never go flat black, gentle distance fade into the clear
// color so the hero reads against any Compose background.
layout(location = 0) in vec3 vNormal;
layout(location = 1) in vec2 vUv;
layout(location = 2) in vec3 vWorld;

layout(location = 0) out vec4 outColor;

layout(set = 0, binding = 0) uniform Ubo {
    mat4 viewProj;
    vec3 sunDir;
    vec3 camPos;
    mat4 bones[32];
} ubo;

layout(set = 0, binding = 1) uniform sampler2D albedo;

const vec3 SKY = vec3(0.45, 0.68, 0.94);

void main() {
    vec3 albedoCol = texture(albedo, vUv).rgb;
    vec3 n = normalize(vNormal);
    vec3 sun = normalize(ubo.sunDir);
    // Front faces get little direct sun (sun points down-back), so carry them
    // with a strong hemisphere ambient + fill — heroes must read brightly
    // from the turntable's front view.
    float ndl = clamp(dot(n, -sun), 0.0, 1.0);
    float hemi = 0.65 + 0.35 * clamp(n.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 col = albedoCol * (0.55 + 0.45 * ndl) * hemi + albedoCol * 0.25;
    float dist = length(vWorld - ubo.camPos);
    float fog = 1.0 - exp(-dist * 0.02);
    outColor = vec4(mix(col, SKY, clamp(fog, 0.0, 1.0)), 1.0);
}
