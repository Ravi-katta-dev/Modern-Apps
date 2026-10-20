#version 300 es
// Textured Yuu avatar fragment shader (GLES 3.0 port of yuu_avatar.frag):
// albedo sample, single fixed sun plus a soft fill, gentle distance fade into
// the clear color. Same lighting as the Vulkan twin.
precision mediump float;
precision mediump sampler2D;

in vec3 vNormal;
in vec2 vUv;
in vec3 vWorld;

out vec4 outColor;

uniform vec3 uSunDir;
uniform vec3 uCamPos;
uniform sampler2D uAlbedo;

const vec3 SKY = vec3(0.45, 0.68, 0.94);

void main() {
    vec3 albedoCol = texture(uAlbedo, vUv).rgb;
    vec3 n = normalize(vNormal);
    vec3 sun = normalize(uSunDir);
    float ndl = clamp(dot(n, -sun), 0.0, 1.0);
    float hemi = 0.65 + 0.35 * clamp(n.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 col = albedoCol * (0.55 + 0.45 * ndl) * hemi + albedoCol * 0.25;
    float dist = length(vWorld - uCamPos);
    float fog = 1.0 - exp(-dist * 0.02);
    outColor = vec4(mix(col, SKY, clamp(fog, 0.0, 1.0)), 1.0);
}
