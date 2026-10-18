#version 300 es
// Flat shading plus distance fog (GLES 3.0 port of kart.frag): one fixed sun
// direction, no shadow maps. Karts read as solid boxes against the track; the
// fog melts the far side of the oval into the sky clear color so the pop-in
// line never shows. Same `ndl` lighting + `exp` fog 0.0045 to
// SKY(0.45, 0.68, 0.94) as the Vulkan twin.
precision mediump float;

in vec3 vColor;
in vec3 vWorld;

out vec4 outColor;

uniform mat4 uViewProj;
uniform vec3 uSunDir;
uniform vec3 uCamPos;

const vec3 SKY = vec3(0.45, 0.68, 0.94);

void main() {
    vec3 up = vec3(0.0, 1.0, 0.0);
    float ndl = 0.72 + 0.28 * clamp(dot(up, normalize(uSunDir)), 0.0, 1.0);
    vec3 col = vColor * ndl;
    float dist = length(vWorld - uCamPos);
    float fog = 1.0 - exp(-dist * 0.0045);
    outColor = vec4(mix(col, SKY, clamp(fog, 0.0, 1.0)), 1.0);
}
