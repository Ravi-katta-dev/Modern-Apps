#version 450

// Globe fill: bends flat tile-local triangles onto the orthographic sphere.
//
// Same inputs as `fill.vert` (position-only tile-local 0..1 in `inPosition.xy`),
// but the matrix is the globe matrix from `Camera::globe_tile_to_clip`, not the
// flat tile matrix: its linear 2x2 carries the globe radius (Dp), its third
// row carries the tile's normalised-world origin + span (ox, oy, span), and its
// translation column carries the camera centre lon/lat (degrees, m[12]/m[13])
// for the sphere basis. The vertex reconstructs its lon/lat from those,
// projects through the CPU-matching `globe_point` basis, and emits clip + a
// real depth (near-side nearer).
//
// `misc`/`morph` keep their flat meaning (tile px in `misc.x`, edge AA in
// `misc.y`, tile fade in `morph.x`, draped-z scale in `morph.z`; `morph.y`
// keeps the dash phase speed the line fragment reads) — the limb discard is
// the only fragment difference from `fill.frag`, so opacity, fade and coverage
// match the flat map exactly. Far-side vertices (z < 0) are emitted at the far
// plane and the fragment stage discards wherever the interpolated z is negative,
// so straddling triangles cut cleanly at the limb. Depth is `1 - z`: the
// sub-camera point is nearest (0), the limb farthest (1). Screen y follows the
// CPU anchor convention (`halfH - y`, north up).

layout(location = 0) in vec3 inPosition;

layout(push_constant) uniform Push {
    mat4 tileToClip;
    vec4 color;
    vec4 line;
    vec4 misc;
    vec4 morph;
} push;

layout(location = 0) out float outZ;

const float PI = 3.141592653589793;

// `globe_tile_to_clip` packs (ox, oy, span) into m[2], m[6], m[10]: row 2 of
// columns 0..2, i.e. `tileToClip[0][2]`, `[1][2]`, `[2][2]` in GLSL.
// Translation column (m[12]/m[13]): centre lon/lat in degrees.

void main() {
    float ox = push.tileToClip[0][2];
    float oy = push.tileToClip[1][2];
    float span = push.tileToClip[2][2];
    // Tile-local (u, v) -> normalised world (0..1 across the planet).
    float wx = ox + inPosition.x * span;
    float wy = oy + inPosition.y * span;
    // Normalised world -> lon/lat (Web Mercator inverse, y down).
    float lon = wx * 360.0 - 180.0;
    float n = PI - 2.0 * PI * wy;
    // atan(sinh(n)): wy is in [0,1] so n in [-PI, PI] and sinh is bounded.
    float lat = atan((exp(n) - exp(-n)) * 0.5) * 180.0 / PI;
    // Sphere basis from the matrix translation column (CPU-side centre).
    float cLon = push.tileToClip[3][0];
    float cLat = push.tileToClip[3][1];
    float latR = radians(lat);
    float dLon = radians(lon - cLon);
    float cLatR = radians(cLat);
    // Must match `camera::globe_point`.
    float sy = sin(cLatR);
    float cy = cos(cLatR);
    float x = cos(latR) * sin(dLon);
    float y = cy * sin(latR) - sy * cos(latR) * cos(dLon);
    float z = sy * sin(latR) + cy * cos(latR) * cos(dLon);
    // Globe radius (Dp) from the matrix scale: m[0] = kx * r with kx = 2/width.
    // The record path pushes morph = (fade, halfW, r, halfH) on the globe branch.
    float hw = push.morph.y;
    float hh = push.morph.w;
    float r = push.morph.z;
    float kx = push.tileToClip[0][0] / max(r, 1e-6);
    float ky = push.tileToClip[1][1] / max(r, 1e-6);
    // Screen point in Dp: centre + sphere offset, y-up like the CPU anchor math
    // (`halfH - y`), so north renders up.
    float sx = x * r;
    float sy2 = y * r;
    float cx = kx * sx + (kx * hw - 1.0);
    float cyy = -(ky * sy2) + (ky * hh - 1.0);
    // Depth: sub-camera point (z=1) -> 0, limb (z=0) -> 1, so nearer wins LESS.
    float depth = clamp(1.0 - z, 0.0, 1.0);
    outZ = z;
    if (z < 0.0) {
        // Far side: emit at far depth so the triangle clips away.
        gl_Position = vec4(cx, cyy, 1.0, 1.0);
    } else {
        gl_Position = vec4(cx, cyy, depth, 1.0);
    }
}
