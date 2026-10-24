#version 450

// Moon vertex: the shared unit quad, scaled to the globe disc.
//
// The record path pushes a matrix that maps the quad's -1..1 to the disc's
// bounding square (radius in clip units, centred on the viewport). Everything
// spherical — the limb cut, the hemisphere z, the normal, the depth — is done
// per fragment in `moon.frag`: the quad has only four corners, all outside the
// unit disc, so a per-vertex disc test would discard the whole moon.
//
// Push layout (the stock `Push` block):
//   `tileToClip` the disc matrix described above.
//   `color.xy` the camera centre lon/lat (degrees), read by the fragment stage.
//   `misc.z` the DEM relief strength, read by the fragment stage.
layout(location = 0) in vec3 inLocal;
layout(location = 0) out vec2 outDisc;
layout(push_constant) uniform Push {
    mat4 tileToClip;
    vec4 color;
    vec4 line;
    vec4 misc;
    vec4 morph;
} push;

void main() {
    outDisc = inLocal.xy;
    gl_Position = push.tileToClip * vec4(inLocal.xy, 0.0, 1.0);
}
