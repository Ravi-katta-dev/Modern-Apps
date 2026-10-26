// Device parity fixtures (vision and Supertonic work). See `parity.rs` for the helpers.
// Blocked, split, full-window and row-path convolutions, int8 and fp16.

/// 2x2 stride-2 transposed convolutions on the blocked path (`in1` = 1): the ppocr_det head
/// pair (24 -> 6 -> 1 channels, ragged channel blocks) over an odd 19x37 input, and a fused
/// residual onto the second.
#[test]
#[ignore = "needs a Vulkan device"]
fn blocked_2x2_transposed_convs_agree_with_the_reference() {
    let input = spread(24 * 19 * 37, 0.5);
    let source = Invented::new(6);
    let plan = build(&source, &[Shape::new(24, 19, 37)], |b, ids| {
        b.spatial_tiling();
        let x = b.conv_transpose(ids[0], 0, 6, (2, 2), (2, 2), (0, 0, 0, 0), Act::Relu);
        let y = b.conv_transpose(x, 2, 6, (2, 2), (2, 2), (0, 0, 0, 0), Act::None);
        let z = b.add(y, y);
        b.conv_transpose(z, 4, 1, (2, 2), (2, 2), (0, 0, 0, 0), Act::Sigmoid)
    });
    let mut seen = 0;
    for op in &plan.ops {
        if let crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvTranspose, push, .. } = op {
            assert_eq!(push.in1, 1, "a 2x2 transpose missed the blocked path");
            seen += 1;
        }
    }
    assert_eq!(seen, 3);
    compare("blocked 2x2 transposed convs", plan, source.into_data(), &[&input]);
}

/// `conv_point_int8.comp`'s split path (`in1` = 1, two channels per invocation), which a GEMM
/// with fewer than 256 32 x 32 tiles takes: 40 channels leave the last 16-channel tile part empty,
/// and 52 / 49 positions cover the aligned and the ragged activation loads.
#[test]
#[ignore = "needs a Vulkan device"]
fn a_split_int8_gemm_agrees_with_the_reference() {
    let (out_channels, in_channels) = (40u32, 64u32);
    let kernel: Vec<i8> =
        (0..(out_channels * in_channels) as i32).map(|i| ((i * 37) % 251 - 125) as i8).collect();
    let scales: Vec<f32> = (0..out_channels).map(|c| 0.007_812_5 * (1.0 + (c % 7) as f32)).collect();
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I8(vec![out_channels, in_channels, 1, 1], kernel),
            Fixture::F16(vec![out_channels], scales),
            Fixture::F16(vec![out_channels], spread(out_channels as usize, 0.5)),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the fixture blob parses");
    for width in [52u32, 49] {
        let input = spread((in_channels * width) as usize, 1.3);
        let mut builder = Builder::new(&weights);
        let first = builder.input(Shape::new(in_channels, 1, width));
        let last = builder.conv_int8(
            first, 0, out_channels, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::Gelu,
        );
        let plan = builder.finish(&[last]).expect("the split plan builds");
        assert!(plan.ops.iter().any(|op| matches!(
            op,
            crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvPointInt8, push, .. }
                if push.in1 == 1
        )));
        let what = format!("a split int8 gemm over {width}");
        compare(&what, plan, weights.data().to_vec(), &[&input]);
    }
}

/// `conv_int8.comp`'s blocked path (`in1` = 1): the whisper stem's stride-2 1x3 at a ragged
/// width and channel count, a stride-1 1x3, and a 4x4 stride-4 patch embed (clip) over a
/// ragged map.
#[test]
#[ignore = "needs a Vulkan device"]
fn blocked_int8_convs_agree_with_the_reference() {
    #[allow(clippy::type_complexity)]
    let cases: [(&str, u32, u32, Shape, (u32, u32), (u32, u32), (u32, u32, u32, u32), u32, Act); 3] = [
        ("stride-2 1x3", 10, 6, Shape::new(6, 1, 37), (1, 3), (1, 2), (0, 1, 0, 1), 1, Act::Gelu),
        ("stride-1 1x3", 12, 5, Shape::new(5, 1, 29), (1, 3), (1, 1), (0, 1, 0, 1), 1, Act::None),
        ("4x4 stride-4", 6, 3, Shape::new(3, 18, 22), (4, 4), (4, 4), (0, 0, 0, 0), 1, Act::None),
    ];
    for (what, out_channels, in_channels, shape, kernel, stride, pad, group, act) in cases {
        let taps = (in_channels / group) * kernel.0 * kernel.1;
        let weights: Vec<i8> =
            (0..(out_channels * taps) as i32).map(|i| ((i * 37) % 251 - 125) as i8).collect();
        let scales: Vec<f32> =
            (0..out_channels).map(|c| 0.003_906_25 * (1.0 + (c % 5) as f32)).collect();
        let blob = write_mixed(
            graph::SUPERTONIC_VE,
            &[
                Fixture::I8(vec![out_channels, in_channels / group, kernel.0, kernel.1], weights),
                Fixture::F16(vec![out_channels], scales),
                Fixture::F16(vec![out_channels], spread(out_channels as usize, 0.5)),
            ],
        );
        let source = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the fixture blob parses");
        let input = spread(shape.len() as usize, 0.7);
        let mut builder = Builder::new(&source);
        let first = builder.input(shape);
        let last =
            builder.conv_int8(first, 0, out_channels, kernel, stride, (1, 1), pad, group, act);
        let plan = builder.finish(&[last]).expect("the blocked int8 plan builds");
        assert!(plan.ops.iter().any(|op| matches!(
            op,
            crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvInt8, push, .. }
                if push.in1 == 1
        )), "{what} missed the blocked path");
        compare(&format!("a blocked int8 {what}"), plan, source.data().to_vec(), &[&input]);
    }
}

/// A 1x1 with a fused PReLU on `conv_point.comp` (MobileFaceNet's projections): 20 channels
/// and 15x15 positions are ragged against the tile, and the slope is read per channel.
#[test]
#[ignore = "needs a Vulkan device"]
fn a_pointwise_conv_with_prelu_agrees_with_the_reference() {
    let input = spread(24 * 15 * 15, 3.0);
    let source = Invented::new(3);
    let plan = build(&source, &[Shape::new(24, 15, 15)], |b, ids| {
        b.conv(ids[0], 0, 20, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::PRelu(2))
    });
    assert!(plan.ops.iter().any(|op| matches!(
        op,
        crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvPoint, .. }
    )));
    compare("a pointwise conv with PReLU", plan, source.into_data(), &[&input]);
}

/// Either side of the split threshold: 504 x 481 is 16 x 16 = 256 tiles and stays on the four-row
/// path; 504 x 450 is 16 x 15 = 240 and splits. Ragged on purpose: 504 channels leave the last
/// 16- and 32-channel tiles part empty, and neither width is a multiple of four.
#[test]
#[ignore = "needs a Vulkan device"]
fn int8_gemms_either_side_of_the_split_threshold_agree_with_the_reference() {
    let (out_channels, in_channels) = (504u32, 16u32);
    let kernel: Vec<i8> =
        (0..(out_channels * in_channels) as i32).map(|i| ((i * 41) % 247 - 123) as i8).collect();
    let scales: Vec<f32> = (0..out_channels).map(|c| 0.003_906_25 * (1.0 + (c % 9) as f32)).collect();
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I8(vec![out_channels, in_channels, 1, 1], kernel),
            Fixture::F16(vec![out_channels], scales),
            Fixture::F16(vec![out_channels], spread(out_channels as usize, 0.9)),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the fixture blob parses");
    for (width, split) in [(481u32, 0u32), (450, 1)] {
        let input = spread((in_channels * width) as usize, 0.2);
        let mut builder = Builder::new(&weights);
        let first = builder.input(Shape::new(in_channels, 1, width));
        let last = builder.conv_int8(
            first, 0, out_channels, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::None,
        );
        let plan = builder.finish(&[last]).expect("the plan builds");
        assert!(plan.ops.iter().any(|op| matches!(
            op,
            crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvPointInt8, push, .. }
                if push.in1 == split
        )), "width {width} should take in1 = {split}");
        let what = format!("an int8 gemm {out_channels}x{width} at in1 = {split}");
        compare(&what, plan, weights.data().to_vec(), &[&input]);
    }
}

/// A kernel covering its whole input (mbf's 7x7 GDC) lowers to `conv_point.comp` over the
/// flattened input; a non-square 5x7 window and 20 output channels keep it ragged.
#[test]
#[ignore = "needs a Vulkan device"]
fn a_full_window_conv_agrees_with_the_reference() {
    let input = spread(24 * 5 * 7, 2.0);
    let source = Invented::new(2);
    let plan = build(&source, &[Shape::new(24, 5, 7)], |b, ids| {
        b.spatial_tiling();
        b.conv(ids[0], 0, 20, (5, 7), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::Relu)
    });
    assert!(plan.ops.iter().any(|op| matches!(
        op,
        crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvPoint, push, .. }
            if push.in_c == 24 * 5 * 7
    )));
    compare("a full-window conv", plan, source.into_data(), &[&input]);
}

/// The Supertonic vocoder head's shape on the blocked path: one row, a 1x3 edge-padded on the
/// left only (2, 0) with PReLU, over 37 columns and 10 ragged output channels.
#[test]
#[ignore = "needs a Vulkan device"]
fn a_blocked_edge_padded_1x3_agrees_with_the_reference() {
    let input = spread(8 * 37, 0.4);
    let source = Invented::new(3);
    let plan = build(&source, &[Shape::new(8, 1, 37)], |b, ids| {
        b.spatial_tiling();
        b.edge_padding();
        b.conv(ids[0], 0, 10, (1, 3), (1, 1), (1, 1), (0, 2, 0, 0), 1, Act::PRelu(2))
    });
    assert_eq!(conv_selectors(&plan), [0, 1, 0], "the edge-padded 1x3 missed the blocked path");
    compare("a blocked edge-padded 1x3", plan, source.into_data(), &[&input]);
}

/// Whisper's stem shape on `conv_point_int8.comp`'s row path (`in1` = 2): one-row 1x3 int8
/// convolutions padded by one, at stride 1 and 2, over ragged widths and channel counts.
#[test]
#[ignore = "needs a Vulkan device"]
fn row_int8_gemms_agree_with_the_reference() {
    for (what, out_channels, in_channels, width, stride, act) in [
        ("stride 1", 20u32, 16u32, 37u32, 1u32, Act::Gelu),
        ("stride 2", 36, 32, 45, 2, Act::None),
    ] {
        let weights: Vec<i8> = (0..(out_channels * in_channels * 3) as i32)
            .map(|i| ((i * 37) % 251 - 125) as i8)
            .collect();
        let scales: Vec<f32> =
            (0..out_channels).map(|c| 0.003_906_25 * (1.0 + (c % 5) as f32)).collect();
        let blob = write_mixed(
            graph::SUPERTONIC_VE,
            &[
                Fixture::I8(vec![out_channels, in_channels, 1, 3], weights),
                Fixture::F16(vec![out_channels], scales),
                Fixture::F16(vec![out_channels], spread(out_channels as usize, 0.5)),
            ],
        );
        let source = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the fixture blob parses");
        let input = spread((in_channels * width) as usize, 0.9);
        let mut builder = Builder::new(&source);
        let first = builder.input(Shape::new(in_channels, 1, width));
        let last = builder.conv_int8(
            first, 0, out_channels, (1, 3), (1, stride), (1, 1), (0, 1, 0, 1), 1, act,
        );
        let plan = builder.finish(&[last]).expect("the row gemm plan builds");
        assert!(plan.ops.iter().any(|op| matches!(
            op,
            crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvPointInt8, push, .. }
                if push.in1 == 2
        )), "{what} missed the row path");
        compare(&format!("a row int8 gemm, {what}"), plan, source.data().to_vec(), &[&input]);
    }
}


/// `conv_point.comp`'s split path (`in1` = 1, two channels per lane) for GEMMs with few tiles:
/// the Supertonic text encoder's 56 positions, 40 output channels leaving the last 16-channel
/// tile part empty, and a fused residual.
#[test]
#[ignore = "needs a Vulkan device"]
fn a_split_pointwise_conv_agrees_with_the_reference() {
    let input = spread(64 * 56, 1.1);
    let source = Invented::new(4);
    let plan = build(&source, &[Shape::new(64, 1, 56)], |b, ids| {
        let x = b.conv(ids[0], 0, 40, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::Relu);
        let y = b.conv(x, 2, 40, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::None);
        b.add(x, y)
    });
    assert!(plan.ops.iter().any(|op| matches!(
        op,
        crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvPoint, push, .. } if push.in1 == 1
    )));
    compare("a split pointwise conv", plan, source.into_data(), &[&input]);
}