// Device parity fixtures (Gemma work). See `parity.rs` for the helpers.
//
// Gemma-4 decode is ~205 int4 GEMV projections plus 70 int8 per-layer
// projections a step (`analysis/litertlm-decode-gap.md`: ConvVecInt4 97 ms,
// ConvVecInt8 3 ms, RmsNorm since fixed by row-parallel reductions). The
// int4 GEMV (`shaders/conv_vec_int4.comp`) is the next kernel to change —
// the wide-load / read-only `f16vec4` activation pattern that took
// `conv_vec_int8` from 30 to 19 ms and `conv_vec_q2k` from 1738 to 45 ms —
// so these pin its numerics at the exact shapes decode runs before the
// shader is touched: 1536-wide down/O, 2048-wide sliding-Q, 12288-wide slim
// gate, the int8 per-layer pair (256x1536 gate, 1536x256 projection), and
// the 8960x1536 device-combine projection (`nets::gemma4_gather`).
//
// All are single-position, so `Builder` lowers each to the `ConvVec` shader
// — asserted per fixture, as the existing int4/int8 fixtures do.

#[test]
#[ignore = "needs a Vulkan device"]
fn a_gemma_down_projection_gemv_agrees_with_the_reference() {
    // `down` / `o_proj`: 1536 out over 1536 taps, 48 whole blocks. The
    // 29.3%-of-decode-bytes bucket (`conv_vec_int4.comp` header table).
    let out_channels = 1536u32;
    let in_channels = 1536u32;
    let blocks = in_channels.div_ceil(32);
    let kernel: Vec<i8> =
        (0..(out_channels * in_channels) as i32).map(|i| ((i * 7) % 16 - 8) as i8).collect();
    let scales: Vec<f32> =
        (0..out_channels * blocks).map(|i| 0.007_812_5 * (1.0 + (i % 5) as f32)).collect();
    let biases: Vec<f32> = spread(out_channels as usize, 1.9);
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I4(vec![out_channels, in_channels, 1, 1], kernel),
            Fixture::F16(vec![out_channels, blocks], scales),
            Fixture::F16(vec![out_channels], biases),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the int4 fixture parses");
    let input = spread(in_channels as usize, 0.7);
    let mut builder = Builder::new(&weights);
    let first = builder.input(Shape::new(in_channels, 1, 1));
    let last = builder.conv_int4(first, 0, out_channels, Act::None);
    let plan = builder.finish(&[last]).expect("the down-projection fixture plan builds");
    assert!(
        plan.ops.iter().any(|op| matches!(
            op,
            crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvVecInt4, .. }
        )),
        "a single-position int4 convolution must lower to the gemv shader",
    );
    compare("a gemma down-projection gemv", plan, weights.data().to_vec(), &[&input]);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_gemma_query_projection_gemv_agrees_with_the_reference() {
    // Sliding-layer `q_proj`: 2048 out (8 heads x head_dim 256) over 1536
    // taps. 2048 / ROWS 8 = 256 workgroups — comfortably occupied, unlike
    // the 256-wide KV projections (32 workgroups, 0.3x of warp slots).
    let out_channels = 2048u32;
    let in_channels = 1536u32;
    let blocks = in_channels.div_ceil(32);
    let kernel: Vec<i8> =
        (0..(out_channels * in_channels) as i32).map(|i| ((i * 11) % 16 - 8) as i8).collect();
    let scales: Vec<f32> =
        (0..out_channels * blocks).map(|i| 0.015_625 * (1.0 + (i % 3) as f32)).collect();
    let biases: Vec<f32> = spread(out_channels as usize, 0.4);
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I4(vec![out_channels, in_channels, 1, 1], kernel),
            Fixture::F16(vec![out_channels, blocks], scales),
            Fixture::F16(vec![out_channels], biases),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the int4 fixture parses");
    let input = spread(in_channels as usize, 0.2);
    let mut builder = Builder::new(&weights);
    let first = builder.input(Shape::new(in_channels, 1, 1));
    let last = builder.conv_int4(first, 0, out_channels, Act::None);
    let plan = builder.finish(&[last]).expect("the query-projection fixture plan builds");
    assert!(
        plan.ops.iter().any(|op| matches!(
            op,
            crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvVecInt4, .. }
        )),
        "a single-position int4 convolution must lower to the gemv shader",
    );
    compare("a gemma query-projection gemv", plan, weights.data().to_vec(), &[&input]);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_gemma_slim_gate_gemv_agrees_with_the_reference() {
    // Slim-layer fused gate: 12288 out over 1536 taps, ~9.4 MB of weights —
    // the 69%-of-decode-bytes bucket with the head. 1536 workgroups at
    // ROWS 8, the shape with enough occupancy to hide load latency.
    let out_channels = 12288u32;
    let in_channels = 1536u32;
    let blocks = in_channels.div_ceil(32);
    let kernel: Vec<i8> =
        (0..(out_channels * in_channels) as i32).map(|i| ((i * 13) % 16 - 8) as i8).collect();
    let scales: Vec<f32> =
        (0..out_channels * blocks).map(|i| 0.007_812_5 * (1.0 + (i % 7) as f32)).collect();
    let biases: Vec<f32> = spread(out_channels as usize, 2.3);
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I4(vec![out_channels, in_channels, 1, 1], kernel),
            Fixture::F16(vec![out_channels, blocks], scales),
            Fixture::F16(vec![out_channels], biases),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the int4 fixture parses");
    let input = spread(in_channels as usize, 1.1);
    let mut builder = Builder::new(&weights);
    let first = builder.input(Shape::new(in_channels, 1, 1));
    let last = builder.conv_int4(first, 0, out_channels, Act::Gelu);
    let plan = builder.finish(&[last]).expect("the slim-gate fixture plan builds");
    assert!(
        plan.ops.iter().any(|op| matches!(
            op,
            crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvVecInt4, .. }
        )),
        "a single-position int4 convolution must lower to the gemv shader",
    );
    compare("a gemma slim-gate gemv", plan, weights.data().to_vec(), &[&input]);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_gemma_per_layer_pair_agrees_with_the_reference() {
    // The two int8 per-layer projections (`nets::gemma4::projection8`):
    // gate 256x1536 with fused GELU, then projection 1536x256. Chained in
    // one plan, as the layer runs them (gate output feeds the mul, whose
    // output feeds the projection — here directly, shapes being the point).
    let (gate_out, proj_out, taps) = (256u32, 1536u32, 1536u32);
    let gate_kernel: Vec<i8> =
        (0..(gate_out * taps) as i32).map(|i| ((i * 29) % 253 - 126) as i8).collect();
    let proj_kernel: Vec<i8> =
        (0..(proj_out * gate_out) as i32).map(|i| ((i * 37) % 251 - 125) as i8).collect();
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I8(vec![gate_out, taps, 1, 1], gate_kernel),
            Fixture::F16(
                vec![gate_out],
                (0..gate_out).map(|c| 0.007_812_5 * (1.0 + (c % 5) as f32)).collect(),
            ),
            Fixture::F16(vec![gate_out], spread(gate_out as usize, 0.8)),
            Fixture::I8(vec![proj_out, gate_out, 1, 1], proj_kernel),
            Fixture::F16(
                vec![proj_out],
                (0..proj_out).map(|c| 0.007_812_5 * (1.0 + (c % 3) as f32)).collect(),
            ),
            Fixture::F16(vec![proj_out], spread(proj_out as usize, 1.4)),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the fixture blob parses");
    let input = spread(taps as usize, 0.6);
    let mut builder = Builder::new(&weights);
    let first = builder.input(Shape::new(taps, 1, 1));
    let gated =
        builder.conv_int8(first, 0, gate_out, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::Gelu);
    let last =
        builder.conv_int8(gated, 3, proj_out, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::None);
    let plan = builder.finish(&[last]).expect("the per-layer-pair fixture plan builds");
    assert_eq!(
        plan.ops
            .iter()
            .filter(|op| matches!(
                op,
                crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvVecInt8, .. }
            ))
            .count(),
        2,
        "both per-layer projections must lower to the gemv shader",
    );
    compare("a gemma per-layer pair", plan, weights.data().to_vec(), &[&input]);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_gemma_combine_projection_gemv_agrees_with_the_reference() {
    // The device-combine projection (`nets::gemma4_gather::build_plan`):
    // 8960 out (35 layers x 256) over 1536 taps, int8 with a per-channel
    // scale. The 13.8M-MAC SGEMV the host runs per token (~94 ms on the
    // Pixel 8); the parity gate (`run_gemma4 --gather-parity`) covers the
    // full plan, this pins the projection kernel at its exact shape.
    let out_channels = 8960u32;
    let in_channels = 1536u32;
    let kernel: Vec<i8> =
        (0..(out_channels * in_channels) as i32).map(|i| ((i * 41) % 247 - 123) as i8).collect();
    let scales: Vec<f32> =
        (0..out_channels).map(|c| 0.000_976_562_5 * (1.0 + (c % 5) as f32)).collect();
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I8(vec![out_channels, in_channels, 1, 1], kernel),
            Fixture::F16(vec![out_channels], scales),
            Fixture::F16(vec![out_channels], spread(out_channels as usize, 1.7)),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the fixture blob parses");
    let input = spread(in_channels as usize, 0.3);
    let mut builder = Builder::new(&weights);
    let first = builder.input(Shape::new(in_channels, 1, 1));
    let last = builder.conv_int8(
        first,
        0,
        out_channels,
        (1, 1),
        (1, 1),
        (1, 1),
        (0, 0, 0, 0),
        1,
        Act::None,
    );
    let plan = builder.finish(&[last]).expect("the combine-projection fixture plan builds");
    assert!(
        plan.ops.iter().any(|op| matches!(
            op,
            crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvVecInt8, .. }
        )),
        "a single-position int8 convolution must lower to the gemv shader",
    );
    compare("a gemma combine-projection gemv", plan, weights.data().to_vec(), &[&input]);
}

/// An int4 1x1 convolution over `positions` columns, checked against the reference; asserts it
/// lowered to the tiled GEMM (`conv_point_int4.comp`).
fn int4_gemm_fixture(label: &str, out_channels: u32, in_channels: u32, positions: u32, act: Act) {
    let blocks = in_channels.div_ceil(32);
    let kernel: Vec<i8> =
        (0..(out_channels * in_channels) as i32).map(|i| ((i * 7 + i / 5) % 16 - 8) as i8).collect();
    let scales: Vec<f32> =
        (0..out_channels * blocks).map(|i| 0.007_812_5 * (1.0 + (i % 5) as f32)).collect();
    let biases: Vec<f32> = spread(out_channels as usize, 1.3);
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I4(vec![out_channels, in_channels, 1, 1], kernel),
            Fixture::F16(vec![out_channels, blocks], scales),
            Fixture::F16(vec![out_channels], biases),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the int4 fixture parses");
    let input = spread((in_channels * positions) as usize, 0.5);
    let mut builder = Builder::new(&weights);
    let first = builder.input(Shape::new(in_channels, 1, positions));
    let last = builder.conv_int4(first, 0, out_channels, act);
    let plan = builder.finish(&[last]).expect("the int4 gemm fixture plan builds");
    assert!(
        plan.ops.iter().any(|op| matches!(
            op,
            crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvPointInt4, .. }
        )),
        "a multi-position int4 convolution must lower to the tiled shader",
    );
    compare(label, plan, weights.data().to_vec(), &[&input]);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_gemma_prefill_query_gemm_agrees_with_the_reference() {
    // Sliding `q_proj` over a 200-token prompt: whole 32x32 tiles, quad-aligned positions.
    int4_gemm_fixture("a gemma prefill query gemm", 2048, 1536, 200, Act::None);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_ragged_prefill_gemm_agrees_with_the_reference() {
    // 37 positions straddle quads and leave a partial tile; 1000 channels leave a partial row
    // tile; 2048 taps is 64 blocks.
    int4_gemm_fixture("a ragged prefill gemm", 1000, 2048, 37, Act::Gelu);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn an_unblocked_prefill_gemm_agrees_with_the_reference() {
    // 100 taps is not a whole number of 32-tap blocks: the per-tap scalar path.
    int4_gemm_fixture("an unblocked prefill gemm", 40, 100, 9, Act::None);
}