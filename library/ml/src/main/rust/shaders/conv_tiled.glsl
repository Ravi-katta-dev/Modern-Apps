//! A dense (`group == 1`) convolution, register-blocked over channels and columns.
//!
//! Included by `conv.comp`, which branches here when `p.in1 == 1` (see `nets::Push::in1`);
//! the untiled body there stays for every net that never opts in with
//! `Builder::spatial_tiling`, and for grouped convolutions.
//!
//! Each invocation owns [`BLOCK_OC`] output channels by [`BLOCK_X`] adjacent columns of one
//! output row, so every weight read feeds eight columns and every activation read feeds four
//! channels. Invocations run along the row first, so neighbouring lanes read neighbouring
//! activations and the same weights.
//!
//! The scalar untiled path is load-issue bound (~12 GMAC/s on a Pixel 8). Stride-1 3x3,
//! dilated 3x3 and 5x5 kernels over quad-aligned rows read each (channel, kernel row) span
//! once as aligned `f16vec4` quads and run every tap from registers (`dense_rows`, ~120
//! GMAC/s); anything else reads masked scalars. Accumulation order per output is the
//! untiled one - input channels, kernel rows, taps - so the two agree to rounding.

#extension GL_EXT_control_flow_attributes : require

layout(std430, binding = 0) readonly buffer ArenaQuad {
    f16vec4 arena_quad[];
};

/// Output channels per invocation. **Must equal `SPATIAL_BLOCK_OC` in `nets`.**
#define BLOCK_OC 4u

/// Adjacent output columns per invocation. **Must equal `SPATIAL_BLOCK_X` in `nets`.**
#define BLOCK_X 8u

// Eight consecutive arena elements from `at`, through the aligned quads covering them. The
// third quad is read only when `at` is unaligned, so a run ending a buffer reads nothing past it.
void eight_at(uint at, out vec4 lo, out vec4 hi) {
    uint q = at >> 2u;
    uint rem = at & 3u;
    f16vec4 a = arena_quad[q];
    f16vec4 b = arena_quad[q + 1u];
    if (rem == 0u) {
        lo = vec4(a);
        hi = vec4(b);
        return;
    }
    f16vec4 c = arena_quad[q + 2u];
    if (rem == 1u) {
        lo = vec4(a.yzw, b.x);
        hi = vec4(b.yzw, c.x);
    } else if (rem == 2u) {
        lo = vec4(a.zw, b.xy);
        hi = vec4(b.zw, c.xy);
    } else {
        lo = vec4(a.w, b.xyz);
        hi = vec4(b.w, c.xyz);
    }
}

// The eight columns' activations for one tap: input row `row`, first column `ix`.
void columns_at(uint row, int ix, bool interior, out vec4 lo, out vec4 hi) {
    if (interior) {
        eight_at(row + uint(ix), lo, hi);
        return;
    }
    float x[BLOCK_X];
    for (uint j = 0u; j < BLOCK_X; j++) {
        int at = ix + int(j * p.stride_w);
        x[j] = (at >= 0 && at < int(p.in_w)) ? float(arena[row + uint(at)]) : 0.0;
    }
    lo = vec4(x[0], x[1], x[2], x[3]);
    hi = vec4(x[4], x[5], x[6], x[7]);
}

/// Quads in the widest window `dense_rows` loads: 8 columns + a 4-column reach + offset 3.
#define WINDOW_QUADS 4

// Element `e` of a window of quads; constant once `dense_rows` is inlined and unrolled.
#define WIN(e) win[(e) >> 2][(e) & 3]

// Dense stride-1 convolution over rows that all share one quad alignment.
//
// Per (input channel, kernel row) the block's whole input span - 8 columns plus the
// kernel's dilated reach - is read as aligned quads, then every kernel tap reuses it from
// registers. Columns outside the row are zeroed by a per-lane mask computed once, so edge
// blocks take the same branch-free path as interior ones (a per-lane edge branch diverged
// most warps). `rem` (the window start's offset into its quad), `kw` and `dil` are
// literals at every call site, so after inlining every window index is a constant.
void dense_rows(
    const int rem, const int kw, const int dil,
    uint oy, int ix0, uint wrow[BLOCK_OC], inout vec4 lo[BLOCK_OC], inout vec4 hi[BLOCK_OC]
) {
    int quads = (rem + int(BLOCK_X) + (kw - 1) * dil + 3) >> 2;
    uint plane = p.in_h * p.in_w;
    uint taps = p.kh * p.kw;
    int c0 = ix0 - rem;
    bvec4 keep[WINDOW_QUADS];
    [[unroll]] for (int q = 0; q < quads; q++) {
        ivec4 c = ivec4(c0 + 4 * q) + ivec4(0, 1, 2, 3);
        keep[q] = equal(clamp(c, ivec4(0), ivec4(int(p.in_w) - 1)), c);
    }
    int last = int((p.in0 + p.in_c * plane - 1u) >> 2u);
    for (uint ic = 0u; ic < p.in_c; ic++) {
        uint channel = p.in0 + ic * plane;
        for (uint ky = 0u; ky < p.kh; ky++) {
            int iy = int(oy * p.stride_h + ky * p.dil_h) - int(p.pad_t);
            if (iy < 0 || iy >= int(p.in_h)) {
                continue;
            }
            int first = (int(channel + uint(iy) * p.in_w) + c0) >> 2;
            vec4 win[WINDOW_QUADS];
            [[unroll]] for (int q = 0; q < quads; q++) {
                vec4 v = vec4(arena_quad[clamp(first + q, 0, last)]);
                win[q] = mix(vec4(0.0), v, keep[q]);
            }
            uint tap0 = ic * taps + ky * p.kw;
            [[unroll]] for (int kx = 0; kx < kw; kx++) {
                int e = rem + kx * dil;
                vec4 xl = vec4(WIN(e), WIN(e + 1), WIN(e + 2), WIN(e + 3));
                vec4 xh = vec4(WIN(e + 4), WIN(e + 5), WIN(e + 6), WIN(e + 7));
                [[unroll]] for (uint i = 0u; i < BLOCK_OC; i++) {
                    float w = float(weights[wrow[i] + tap0 + uint(kx)]);
                    lo[i] += w * xl;
                    hi[i] += w * xh;
                }
            }
        }
    }
}

// `dense_rows` for the four alignments of one kernel shape; `rem` is uniform per dispatch.
#define DENSE_ROWS_ANY(KW, DIL) \
    if (rem == 0) dense_rows(0, KW, DIL, oy, ix0, wrow, lo, hi); \
    else if (rem == 1) dense_rows(1, KW, DIL, oy, ix0, wrow, lo, hi); \
    else if (rem == 2) dense_rows(2, KW, DIL, oy, ix0, wrow, lo, hi); \
    else dense_rows(3, KW, DIL, oy, ix0, wrow, lo, hi);

void tiled_spatial() {
    uint x_blocks = (p.out_w + BLOCK_X - 1u) / BLOCK_X;
    uint oc_blocks = (p.out_c + BLOCK_OC - 1u) / BLOCK_OC;
    uint invocation = global_index();
    if (invocation >= oc_blocks * p.out_h * x_blocks) {
        return;
    }
    uint ox0 = (invocation % x_blocks) * BLOCK_X;
    uint rest = invocation / x_blocks;
    uint oy = rest % p.out_h;
    uint channel_base = (rest / p.out_h) * BLOCK_OC;

    // Channels past the last read the last one's weights; never stored.
    uint rows[BLOCK_OC];
    for (uint i = 0u; i < BLOCK_OC; i++) {
        rows[i] = min(channel_base + i, p.out_c - 1u);
    }
    vec4 lo[BLOCK_OC];
    vec4 hi[BLOCK_OC];
    for (uint i = 0u; i < BLOCK_OC; i++) {
        lo[i] = vec4(float(weights[p.bias + rows[i]]));
        hi[i] = lo[i];
    }

    uint taps = p.kh * p.kw;
    uint plane = p.in_h * p.in_w;
    int ix0 = int(ox0 * p.stride_w) - int(p.pad_l);
    bool interior = p.stride_w == 1u && ix0 >= 0
        && ix0 + int(BLOCK_X - 1u + (p.kw - 1u) * p.dil_w) < int(p.in_w);

    uint wrow[BLOCK_OC];
    for (uint i = 0u; i < BLOCK_OC; i++) {
        wrow[i] = p.weight + rows[i] * p.in_c * taps;
    }

    // Every row of the input starts at the same quad offset, so the window alignment
    // `rem` is one value for the whole dispatch.
    bool aligned_rows = p.stride_w == 1u && (p.in_w & 3u) == 0u && (p.in0 & 3u) == 0u;
    int rem = (int(p.in0) + ix0) & 3;
    if (aligned_rows && p.kw == 3u && p.dil_w == 1u) {
        DENSE_ROWS_ANY(3, 1)
    } else if (aligned_rows && p.kw == 3u && p.dil_w == 2u) {
        DENSE_ROWS_ANY(3, 2)
    } else if (aligned_rows && p.kw == 5u && p.dil_w == 1u) {
        DENSE_ROWS_ANY(5, 1)
    } else {
        for (uint ic = 0u; ic < p.in_c; ic++) {
            uint channel = p.in0 + ic * plane;
            for (uint ky = 0u; ky < p.kh; ky++) {
                int iy = int(oy * p.stride_h + ky * p.dil_h) - int(p.pad_t);
                if (iy < 0 || iy >= int(p.in_h)) {
                    continue;
                }
                uint row = channel + uint(iy) * p.in_w;
                uint tap0 = ic * taps + ky * p.kw;
                for (uint kx = 0u; kx < p.kw; kx++) {
                    vec4 xl, xh;
                    columns_at(row, ix0 + int(kx * p.dil_w), interior, xl, xh);
                    for (uint i = 0u; i < BLOCK_OC; i++) {
                        float w = float(weights[wrow[i] + tap0 + kx]);
                        lo[i] += w * xl;
                        hi[i] += w * xh;
                    }
                }
            }
        }
    }

    uint positions = p.out_h * p.out_w;
    for (uint i = 0u; i < BLOCK_OC; i++) {
        uint channel = channel_base + i;
        if (channel >= p.out_c) {
            continue;
        }
        for (uint j = 0u; j < BLOCK_X; j++) {
            if (ox0 + j >= p.out_w) {
                continue;
            }
            uint index = channel * positions + oy * p.out_w + ox0 + j;
            float value = j < 4u ? lo[i][j] : hi[i][j - 4u];
            arena[p.out_at + index] = float16_t(fused_store(value, p.act, channel, index));
        }
    }
}

// Eight output columns' taps for a depthwise row: stride 1 reads 8 contiguous elements,
// stride 2 reads 16 and keeps the even ones; edges and other strides gather masked scalars.
void depthwise_columns(uint row, int ix, bool interior, out vec4 lo, out vec4 hi) {
    if (interior && p.stride_w == 1u) {
        eight_at(row + uint(ix), lo, hi);
        return;
    }
    if (interior && p.stride_w == 2u) {
        vec4 a, b, c, d;
        eight_at(row + uint(ix), a, b);
        eight_at(row + uint(ix) + 8u, c, d);
        lo = vec4(a.xz, b.xz);
        hi = vec4(c.xz, d.xz);
        return;
    }
    columns_at(row, ix, false, lo, hi);
}

/// A depthwise convolution (`group == in_c == out_c`): one channel by [`BLOCK_X`] columns
/// of one output row per invocation, so each weight is read once per eight outputs.
void tiled_depthwise() {
    uint x_blocks = (p.out_w + BLOCK_X - 1u) / BLOCK_X;
    uint invocation = global_index();
    if (invocation >= p.out_c * p.out_h * x_blocks) {
        return;
    }
    uint ox0 = (invocation % x_blocks) * BLOCK_X;
    uint rest = invocation / x_blocks;
    uint oy = rest % p.out_h;
    uint channel = rest / p.out_h;

    vec4 lo = vec4(float(weights[p.bias + channel]));
    vec4 hi = lo;
    int ix0 = int(ox0 * p.stride_w) - int(p.pad_l);
    bool interior = ix0 >= 0
        && ix0 + int(BLOCK_X * p.stride_w - 1u + (p.kw - 1u) * p.dil_w) < int(p.in_w);
    uint plane = p.in0 + channel * p.in_h * p.in_w;
    uint kernel_at = p.weight + channel * p.kh * p.kw;
    for (uint ky = 0u; ky < p.kh; ky++) {
        int iy = int(oy * p.stride_h + ky * p.dil_h) - int(p.pad_t);
        if (iy < 0 || iy >= int(p.in_h)) {
            continue;
        }
        uint row = plane + uint(iy) * p.in_w;
        uint tap = kernel_at + ky * p.kw;
        for (uint kx = 0u; kx < p.kw; kx++) {
            vec4 xl, xh;
            depthwise_columns(row, ix0 + int(kx * p.dil_w), interior, xl, xh);
            float w = float(weights[tap + kx]);
            lo += w * xl;
            hi += w * xh;
        }
    }

    uint positions = p.out_h * p.out_w;
    for (uint j = 0u; j < BLOCK_X; j++) {
        if (ox0 + j >= p.out_w) {
            continue;
        }
        uint index = channel * positions + oy * p.out_w + ox0 + j;
        float value = j < 4u ? lo[j] : hi[j - 4u];
        arena[p.out_at + index] = float16_t(fused_store(value, p.act, channel, index));
    }
}
