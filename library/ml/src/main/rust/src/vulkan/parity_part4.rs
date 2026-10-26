#[test]
#[ignore = "needs a Vulkan device"]
fn a_q2k_gemv_agrees_with_the_reference() {
    // `conv_vec_q2k.comp` over arbitrary superblock bytes - every scale nibble and code pattern -
    // run once with 1,280 inputs (five superblocks, the word-per-lane path, more units than lanes
    // in the last pass) and once with 300 (not a whole superblock, the per-superblock loop). An
    // odd channel count so the last workgroup's clamped rows must not be stored.
    for (out_channels, in_channels) in [(13u32, 1280u32), (11, 300)] {
        let blocks = in_channels.div_ceil(crate::weights::Q2K_BLOCK);
        let bytes: Vec<u8> = (0..out_channels * blocks * crate::weights::Q2K_BYTES)
            .map(|i| (i.wrapping_mul(2_654_435_761) >> 13) as u8)
            .collect();
        // Small positive `d` and `dmin` so `d*d*q*lo - dmin*d*hi` stays well inside fp16.
        let pairs: Vec<f32> = (0..out_channels * blocks * 2)
            .map(|i| 0.05 + 0.01 * ((i * 7) % 11) as f32)
            .collect();
        let blob = write_mixed(
            graph::SUPERTONIC_VE,
            &[
                Fixture::Q2K(vec![out_channels, in_channels], bytes),
                Fixture::F16(vec![out_channels * blocks * 2], pairs),
                Fixture::F16(vec![out_channels], spread(out_channels as usize, 0.3)),
            ],
        );
        let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the fixture blob parses");
        let input = spread(in_channels as usize, 0.9);
        let mut builder = Builder::new(&weights);
        let first = builder.input(Shape::new(in_channels, 1, 1));
        let last = builder.conv_q2k(first, 0, out_channels, Act::None);
        let plan = builder.finish(&[last]).expect("the q2k gemv plan builds");
        assert!(plan.ops.iter().any(|op| matches!(
            op,
            crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvVecQ2K, .. }
        )));
        compare(&format!("a q2k gemv {out_channels}x{in_channels}"), plan, weights.data().to_vec(), &[&input]);
    }
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_tiled_q2k_convolution_agrees_with_the_reference() {
    // `conv_point_q2k.comp` over arbitrary superblock bytes: two superblocks of taps over 36
    // positions (the word path, two partial tiles of channels) and 300 taps over 10 positions
    // (the element path, a partial superblock and a position count not divisible by four).
    for (out_channels, in_channels, width) in [(40u32, 512u32, 36u32), (13, 300, 10)] {
        let blocks = in_channels.div_ceil(crate::weights::Q2K_BLOCK);
        let bytes: Vec<u8> = (0..out_channels * blocks * crate::weights::Q2K_BYTES)
            .map(|i| (i.wrapping_mul(2_246_822_519) >> 11) as u8)
            .collect();
        let pairs: Vec<f32> = (0..out_channels * blocks * 2)
            .map(|i| 0.04 + 0.01 * ((i * 5) % 9) as f32)
            .collect();
        let blob = write_mixed(
            graph::SUPERTONIC_VE,
            &[
                Fixture::Q2K(vec![out_channels, in_channels], bytes),
                Fixture::F16(vec![out_channels * blocks * 2], pairs),
                Fixture::F16(vec![out_channels], spread(out_channels as usize, 0.6)),
            ],
        );
        let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the fixture blob parses");
        let input = spread((in_channels * width) as usize, 0.25);
        let mut builder = Builder::new(&weights);
        let first = builder.input(Shape::new(in_channels, 1, width));
        let last = builder.conv_q2k(first, 0, out_channels, Act::None);
        let plan = builder.finish(&[last]).expect("the tiled q2k plan builds");
        assert!(plan.ops.iter().any(|op| matches!(
            op,
            crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvPointQ2K, .. }
        )));
        let what = format!("a tiled q2k convolution {out_channels}x{in_channels} over {width}");
        compare(&what, plan, weights.data().to_vec(), &[&input]);
    }
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_wide_gemv_agrees_with_the_reference() {
    // `conv_vec_int8.comp`'s sixteen-tap path over several strides per lane (2,080 inputs is two
    // passes of 1,024 and a partial third) and an odd channel count, so the last workgroup's second
    // row reads a clamped row and must not be stored.
    let (out_channels, in_channels) = (37u32, 2080u32);
    let kernel: Vec<i8> = (0..(out_channels * in_channels) as i32)
        .map(|i| ((i * 29) % 253 - 126) as i8)
        .collect();
    let scales: Vec<f32> = (0..out_channels).map(|c| 0.000_976_562_5 * (1.0 + (c % 5) as f32)).collect();
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I8(vec![out_channels, in_channels, 1, 1], kernel),
            Fixture::F16(vec![out_channels], scales),
            Fixture::F16(vec![out_channels], spread(out_channels as usize, 0.8)),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the fixture blob parses");
    let input = spread(in_channels as usize, 0.6);
    let mut builder = Builder::new(&weights);
    let first = builder.input(Shape::new(in_channels, 1, 1));
    let last = builder.conv_int8(first, 0, out_channels, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::None);
    let plan = builder.finish(&[last]).expect("the gemv plan builds");
    assert!(plan.ops.iter().any(|op| matches!(
        op,
        crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvVecInt8, .. }
    )));
    compare("a wide int8 gemv", plan, weights.data().to_vec(), &[&input]);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn the_gemv_and_tiled_int8_shaders_agree_with_each_other() {
    // The same weights over one position and over 16. `Builder` sends the first to
    // `conv_vec_int8.comp` and the second to `conv_point_int8.comp`, and the wide run's position 0
    // must equal the narrow one — the strongest statement available that adding the lowering
    // changed no numbers, and the one a device can make that the interpreter cannot, since the
    // interpreter serves both from the same `conv_int8`.
    let out_channels = 24u32;
    let in_channels = 64u32;
    let width = 16u32;
    let kernel: Vec<i8> = (0..(out_channels * in_channels) as i32)
        .map(|i| ((i * 53) % 241 - 120) as i8)
        .collect();
    let scales: Vec<f32> = (0..out_channels).map(|c| 0.015_625 * (1.0 + c as f32)).collect();
    let biases: Vec<f32> = spread(out_channels as usize, 0.3);
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I8(vec![out_channels, in_channels, 1, 1], kernel),
            Fixture::F16(vec![out_channels], scales),
            Fixture::F16(vec![out_channels], biases),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the fixture blob parses");

    let narrow = spread(in_channels as usize, 0.4);
    // Channel-major, so position 0 of each channel is every `width`th element.
    let mut wide = vec![0.0f32; (in_channels * width) as usize];
    for channel in 0..in_channels as usize {
        wide[channel * width as usize] = narrow[channel];
    }

    let run = |shape: Shape, input: &[f32]| -> Vec<f32> {
        let mut builder = Builder::new(&weights);
        let first = builder.input(shape);
        let last = builder.conv_int8(
            first,
            0,
            out_channels,
            (1, 1),
            (1, 1),
            (1, 1),
            (0, 0, 0, 0),
            1,
            Act::Relu,
        );
        let plan = builder.finish(&[last]).expect("the comparison plan builds");
        let mut out = on_device(plan, weights.data().to_vec(), &[input]);
        out.pop().expect("one output")
    };
    let one = run(Shape::new(in_channels, 1, 1), &narrow);
    let many = run(Shape::new(in_channels, 1, width), &wide);
    for channel in 0..out_channels as usize {
        let gemv = one[channel];
        let tiled = many[channel * width as usize];
        // Both accumulate in fp32 and store fp16, but in a different order, so the last fp16 place
        // can differ — the same tolerance `matches` applies.
        let tolerance = 1e-3 * gemv.abs().max(1.0);
        assert!(
            (gemv - tiled).abs() <= tolerance,
            "channel {channel}: gemv {gemv} against tiled {tiled}",
        );
    }
}

#[test]
#[ignore = "needs a Vulkan device"]
fn rotary_agrees_with_the_reference() {
    // The half-split convention: `out[j] = x[j] cos - x[j + half] sin`, within each head. A
    // shader that paired adjacent channels instead — the other common convention — gets the
    // same shape and the same magnitude, so only the values catch it.
    let x = spread(8 * 4, 0.0);
    let angles = spread(4 * 4, 1.3);
    agrees(
        "rotary",
        &[Shape::new(8, 1, 4), Shape::new(4, 1, 4)],
        &[&x, &angles],
        &[],
        |b, ids| b.rotary(ids[0], ids[1], 2),
    );
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_folded_constant_agrees_with_the_reference() {
    // The only op that reads nothing from the arena, so it is also the only one whose output
    // offset the shader cannot cross-check against an input it just read.
    let x = spread(4 * 3, 0.4);
    let folded = spread(4 * 3, 2.1);
    agrees(
        "a folded constant",
        &[Shape::new(4, 1, 3)],
        &[&x],
        &[(vec![4, 1, 3], folded)],
        |b, ids| {
            let constant = b.constant(0, Shape::new(4, 1, 3));
            b.add(ids[0], constant)
        },
    );
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_per_channel_shift_agrees_with_the_reference() {
    // `a + b` where b is C x 1 x 1. The mirror of mul_channel, and the one thing Supertonic's
    // four timestep conditioning layers need.
    let x = spread(4 * 3, 0.2);
    let shift = spread(4, 3.0);
    agrees(
        "a per-channel shift",
        &[Shape::new(4, 1, 3), Shape::new(4, 1, 1)],
        &[&x, &shift],
        &[],
        |b, ids| b.add_channel(ids[0], ids[1]),
    );
}

#[test]
#[ignore = "needs a Vulkan device"]
fn attention_agrees_with_the_reference() {
    // Deliberately not self-attention: `Q . K^T` is symmetric when Q == K, so a fixture built
    // from one tensor passes with the operands swapped and with the query and key axes
    // transposed. Four queries against five keys makes the score map rectangular, which pins
    // the orientation as well as the arithmetic.
    let q = spread(8 * 4, 0.0);
    let k = spread(8 * 5, 1.7);
    let v = spread(8 * 5, 2.9);
    agrees(
        "attention",
        &[Shape::new(8, 1, 4), Shape::new(8, 1, 5), Shape::new(8, 1, 5)],
        &[&q, &k, &v],
        &[],
        |b, ids| {
            let scores = b.attn_scores(ids[0], ids[1], 2);
            let probs = b.softmax(scores);
            b.attn_apply(probs, ids[2], 2)
        },
    );
}

#[test]
#[ignore = "needs a Vulkan device"]
fn layer_norm_over_channels_agrees_with_the_reference() {
    // The reduction is strided here, not contiguous: these sequences are [d_model, 1, T], so
    // normalising over channels reads a stride apart. A shader that reduced over W instead
    // still writes plausible unit-variance numbers.
    let x = spread(8 * 5, 0.6);
    let gamma = spread(8, 1.1);
    let beta = spread(8, 2.3);
    agrees(
        "layer norm",
        &[Shape::new(8, 1, 5)],
        &[&x],
        &[(vec![8], gamma), (vec![8], beta)],
        |b, ids| b.layer_norm(ids[0], 0, 1e-5),
    );
}

/// Build `record`'s plan, require every dispatch of `kind` in it to be in row mode, and return it.
///
/// The row-mode fixtures below are only worth anything if the plan actually chose that mode, and
/// the choice is a threshold in `nets` that can move. This makes a fixture that silently fell back
/// to the per-row shader fail instead of passing for the wrong reason.
fn row_mode_plan(
    source: &dyn WeightSource,
    shapes: &[Shape],
    kind: crate::nets::Kind,
    record: impl FnOnce(&mut Builder, &[Id]) -> Id,
) -> Plan {
    let plan = build(source, shapes, record);
    let mut seen = 0;
    for op in &plan.ops {
        if let crate::nets::Op::Dispatch { kind: k, push, .. } = op {
            if *k == kind {
                assert_eq!(push.kw, 1, "{kind:?} is not in row mode: {push:?}");
                seen += 1;
            }
        }
    }
    assert!(seen > 0, "the plan has no {kind:?}");
    plan
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_tiled_score_map_agrees_with_the_reference() {
    // Lengths divisible by four but not by the 32-wide tile, in both axes, so partial tiles run;
    // a cross-attention shape (queries != keys) so the two strides cannot be confused; and a
    // grouped case where two query heads share each key head.
    for (heads, kv_heads, head_dim, queries, keys) in
        [(2u32, 2u32, 8u32, 36u32, 44u32), (4, 2, 12, 40, 68)]
    {
        let q = spread((heads * head_dim * queries) as usize, 0.31);
        let k = spread((kv_heads * head_dim * keys) as usize, -0.23);
        let source = Invented::new(0);
        let plan = row_mode_plan(
            &source,
            &[Shape::new(heads * head_dim, 1, queries), Shape::new(kv_heads * head_dim, 1, keys)],
            crate::nets::Kind::AttnScores,
            |b, ids| {
                if heads == kv_heads {
                    b.attn_scores(ids[0], ids[1], heads)
                } else {
                    b.attn_scores_grouped_prescaled(ids[0], ids[1], heads, kv_heads)
                }
            },
        );
        let what = format!("a tiled score map, {heads}/{kv_heads} heads, {queries}x{keys}");
        compare(&what, plan, source.into_data(), &[&q, &k]);
    }
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_tiled_attention_apply_agrees_with_the_reference() {
    // As above: partial tiles in both axes, queries != keys, and a grouped case.
    for (heads, kv_heads, head_dim, queries, keys) in
        [(2u32, 2u32, 8u32, 40u32, 52u32), (4, 2, 12, 44, 36)]
    {
        // Probabilities need not sum to one for the arithmetic under test.
        let probs = spread((heads * queries * keys) as usize, 0.17);
        let v = spread((kv_heads * head_dim * keys) as usize, 0.53);
        let source = Invented::new(0);
        let plan = row_mode_plan(
            &source,
            &[Shape::new(heads, queries, keys), Shape::new(kv_heads * head_dim, 1, keys)],
            crate::nets::Kind::AttnApply,
            |b, ids| {
                if heads == kv_heads {
                    b.attn_apply(ids[0], ids[1], heads)
                } else {
                    b.attn_apply_grouped(ids[0], ids[1], heads, kv_heads)
                }
            },
        );
        let what = format!("a tiled attention apply, {heads}/{kv_heads} heads, {queries}x{keys}");
        compare(&what, plan, source.into_data(), &[&probs, &v]);
    }
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_row_mode_layer_norm_agrees_with_the_reference() {
    // A decode step's shape - one position of d_model 1,024 - and a short sentence's, five
    // positions, where the channels a lane reads are a stride apart. 1,024 is not a multiple of
    // the lane count times anything convenient on every device, which is the point: the strided
    // loop must cover every channel exactly once whatever the subgroup size.
    for positions in [1u32, 5] {
        let channels = 1024u32;
        let x = spread((channels * positions) as usize, 0.6);
        let given = Given::new(&[
            (vec![channels], spread(channels as usize, 1.1)),
            (vec![channels], spread(channels as usize, 2.3)),
        ])
        .expect("the fixture tensors are consistent");
        let plan = row_mode_plan(
            &given,
            &[Shape::new(channels, 1, positions)],
            crate::nets::Kind::LayerNorm,
            |b, ids| b.layer_norm(ids[0], 0, 1e-5),
        );
        compare(&format!("row-mode layer norm over {positions}"), plan, given.data().to_vec(), &[&x]);
    }
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_row_mode_grouped_rms_norm_agrees_with_the_reference() {
    // Gemma 4's QK-norm at decode: eight heads of 256, each its own row and its own workgroup.
    const HEAD_DIM: u32 = 256;
    const HEADS: u32 = 8;
    let channels = HEAD_DIM * HEADS;
    let input: Vec<f32> = (0..channels)
        .map(|i| spread(channels as usize, 0.9)[i as usize] * ((i / HEAD_DIM) as f32 + 1.0))
        .collect();
    let given = Given::new(&[(vec![HEAD_DIM], spread(HEAD_DIM as usize, 2.5))])
        .expect("the fixture tensors are consistent");
    let plan = row_mode_plan(
        &given,
        &[Shape::new(channels, 1, 1)],
        crate::nets::Kind::RmsNorm,
        |b, ids| b.rms_norm_grouped(ids[0], 0, 1e-6, HEADS),
    );
    compare("a row-mode grouped rms norm", plan, given.data().to_vec(), &[&input]);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_row_mode_softmax_agrees_with_the_reference() {
    // Whisper's decode cross-attention: a few heads, each one query over 1,500 keys. Large, spread
    // values so the max subtraction matters - a row that skipped it overflows `exp`.
    let (heads, keys) = (8u32, 1500u32);
    let x: Vec<f32> = spread((heads * keys) as usize, 0.3).iter().map(|v| v * 40.0).collect();
    let source = Invented::new(0);
    let plan = row_mode_plan(
        &source,
        &[Shape::new(heads, 1, keys)],
        crate::nets::Kind::Softmax,
        |b, ids| b.softmax(ids[0]),
    );
    compare("a row-mode softmax", plan, source.into_data(), &[&x]);
}
