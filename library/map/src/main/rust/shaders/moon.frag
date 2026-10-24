#version 450

// Moon fragment: LROC color textured by selenographic lon/lat, shaded by the
// LDEM-derived normal with a fixed sun direction.
//
// `inDisc` is -1..1 across the disc's bounding square (y down, Vulkan clip).
// Outside the unit circle is discarded; inside, the orthographic hemisphere z
// and the sphere normal are recovered per fragment, and the lon/lat inverts
// through the same east/north/up basis the globe math uses
// (`globe_point`/`globe_lonlat`). The color texture is equirectangular
// -180..180/-90..90. The DEM (same layout, RG-packed uint16 half-metres +
// 20000) perturbs the normal for relief shading.
//
// Lighting is a single fixed sun from the east-equatorial direction: enough to
// read relief, deliberately not a day/night terminator (no sun-position input
// exists on this path yet). Limb darkening fakes the spherical falloff the
// flat texture lacks.

layout(location = 0) in vec2 inDisc;
layout(location = 0) out vec4 outColor;

layout(push_constant) uniform Push {
    mat4 tileToClip;
    // xy: camera centre lon/lat (degrees) — the visible-hemisphere centre.
    vec4 color;
    vec4 line;
    // z: DEM relief strength (normal nudge per metre).
    vec4 misc;
    vec4 morph;
} push;
layout(set = 0, binding = 0) uniform sampler2D moonColor;
layout(set = 0, binding = 1) uniform sampler2D moonDem;

const float PI = 3.141592653589793;

// The height bytes are split across R and G, so filtering would blend the high
// byte of one texel with another's and spike at every byte rollover. Fetch the
// exact texel instead: longitude wraps, latitude clamps at the poles.
float demMetres(vec2 uv) {
    ivec2 size = textureSize(moonDem, 0);
    ivec2 p = ivec2(
        int(fract(uv.x) * float(size.x)) % size.x,
        clamp(int(uv.y * float(size.y)), 0, size.y - 1));
    vec4 t = texelFetch(moonDem, p, 0);
    return (round(t.r * 255.0) * 256.0 + round(t.g * 255.0) - 20000.0) * 0.5;
}

void main() {
    // Disc y-down to north-up.
    vec2 en = vec2(inDisc.x, -inDisc.y);
    float r2 = dot(en, en);
    if (r2 > 1.0) discard;
    float z = sqrt(1.0 - r2);
    vec3 sphereNormal = vec3(en, z);
    // Nearer (larger z) wins LESS, matching the globe tiles' depth regime.
    gl_FragDepth = 1.0 - z * 0.5;

    // Invert the east/north/up basis (mirrors `globe_lonlat`).
    float cLatR = radians(push.color.y);
    float sy = sin(cLatR);
    float cy = cos(cLatR);
    float ey = cy * en.y + sy * z;
    float ez = -sy * en.y + cy * z;
    float lat = asin(clamp(ey, -1.0, 1.0)) * 180.0 / PI;
    float lon = push.color.x + atan(en.x, ez) * 180.0 / PI;
    lon = mod(lon + 180.0, 360.0) - 180.0;
    vec2 uv = vec2((lon + 180.0) / 360.0, (90.0 - lat) / 180.0);
    vec3 albedo = texture(moonColor, uv).rgb;

    // Forward differences one DEM texel east and south. Equirectangular texels
    // span equal angles on both axes, so the two gradients share one scale.
    vec2 texel = 1.0 / vec2(textureSize(moonDem, 0));
    float hC = demMetres(uv);
    float hX = demMetres(uv + vec2(texel.x, 0.0));
    float hY = demMetres(uv + vec2(0.0, texel.y));
    float relief = push.misc.z;
    // Rising east tilts the normal west; rising south (uv.y grows southward)
    // tilts it north.
    vec3 n = normalize(sphereNormal + vec3(-(hX - hC), hY - hC, 0.0) * relief);
    vec3 sun = normalize(vec3(0.8, 0.25, 0.55));
    float diff = clamp(dot(n, sun), 0.0, 1.0);
    float shade = (0.25 + 0.75 * diff) * (0.35 + 0.65 * z);
    outColor = vec4(albedo * shade, 1.0);
}
