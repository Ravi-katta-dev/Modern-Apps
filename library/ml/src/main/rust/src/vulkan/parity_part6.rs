// Device parity fixtures (vision and Supertonic work). See `parity.rs` for the helpers.

// Tiled spatial convolutions (`shaders/conv_tiled.glsl` via `Builder::spatial_tiling`).
//
// Each fixture builds a one- or two-op plan with tiling opted in and requires the
// device to agree with the host interpreter, which serves `Kind::Conv` from the
// untiled `conv` — so these compare the two shader paths against each other through
// the same oracle. Sizes are small enough for the debug interpreter but ragged
// against the 32x32 tile on purpose: channels, positions and rows that are not
// multiples of the tile are where the clamping and masking go wrong.
//
// Real-size coverage comes from `profile_vision` checksums on the Pixel (the bar
// requires end-to-end outputs unchanged there); a whole 320x320 map through the
// debug interpreter is hours, not a fixture.

/// A dense 3x3 at stride 1 with ragged channels and positions: 24 channels is three
/// quarters of a channel tile, 36x36 is 1296 positions with a 16 tail.
#[test]
#[ignore = "needs a Vulkan device"]
fn a_tiled_dense_conv_agrees_with_the_reference() {
    let input = spread(8 * 36 * 36, 0.0);
    agrees_invented(
        "a tiled dense 3x3",
        2,
        &[Shape::new(8, 36, 36)],
        &[&input],
        |b, ids| {
            b.spatial_tiling();
            b.conv(ids[0], 0, 24, (3, 3), (1, 1), (1, 1), (1, 1, 1, 1), 1, Act::Relu)
        },
    );
}

/// A strided 3x3 with the selfie stem's asymmetric padding: nothing above or left,
// one below and right. Symmetric padding gives the same output size and a mask
// shifted half a pixel, so the asymmetry is the thing under test.
#[test]
#[ignore = "needs a Vulkan device"]
fn a_tiled_strided_conv_keeps_its_asymmetric_padding() {
    let input = spread(8 * 32 * 32, 1.0);
    agrees_invented(
        "a tiled strided 3x3 with asymmetric pads",
        2,
        &[Shape::new(8, 32, 32)],
        &[&input],
        |b, ids| {
            b.spatial_tiling();
            b.conv(ids[0], 0, 16, (3, 3), (2, 2), (1, 1), (0, 0, 1, 1), 1, Act::Relu)
        },
    );
}

/// Depthwise 3x3s at strides 1 and 2, the SCRFD/MobileFaceNet shape: one group per
/// channel, so the kernel is `[channels, 1, 3, 3]` and each output channel must see
/// exactly one input channel.
#[test]
#[ignore = "needs a Vulkan device"]
fn tiled_depthwise_convs_agree_with_the_reference() {
    let input = spread(16 * 24 * 24, 2.0);
    agrees_invented(
        "tiled depthwise 3x3s",
        4,
        &[Shape::new(16, 24, 24)],
        &[&input],
        |b, ids| {
            b.spatial_tiling();
            let x = b.conv(ids[0], 0, 16, (3, 3), (1, 1), (1, 1), (1, 1, 1, 1), 16, Act::Relu);
            b.conv(x, 2, 16, (3, 3), (2, 2), (1, 1), (1, 1, 1, 1), 16, Act::Relu)
        },
    );
}

/// A dilated 3x3 (U^2-Netp's RSU bottoms) and a dense 5x5 (MODNet's branch heads),
/// both with ragged extents: 30x30 is 900 positions with a 4 tail.
#[test]
#[ignore = "needs a Vulkan device"]
fn tiled_dilated_and_5x5_convs_agree_with_the_reference() {
    let input = spread(12 * 30 * 30, 3.0);
    agrees_invented(
        "tiled dilated 3x3 and dense 5x5",
        4,
        &[Shape::new(12, 30, 30)],
        &[&input],
        |b, ids| {
            b.spatial_tiling();
            let x = b.conv(ids[0], 0, 12, (3, 3), (1, 1), (2, 2), (2, 2, 2, 2), 1, Act::Relu);
            b.conv(x, 2, 12, (5, 5), (1, 1), (1, 1), (2, 2, 2, 2), 1, Act::Relu)
        },
    );
}

/// A grouped 3x3 that is neither dense nor depthwise (two channels per group, the
/// MobileFaceNet stem shape), with a fused PReLU: the slope read is per channel,
// so a row-indexing error lands two layers on one slope's value.
#[test]
#[ignore = "needs a Vulkan device"]
fn a_tiled_grouped_conv_with_prelu_agrees_with_the_reference() {
    let input = spread(8 * 20 * 20, 4.0);
    agrees_invented(
        "a tiled grouped 3x3 with PReLU",
        3,
        &[Shape::new(8, 20, 20)],
        &[&input],
        |b, ids| {
            b.spatial_tiling();
            // Weight at 0, bias at 1, slope at 2: `conv` reads the bias after the
            // weight, and the slope index rides the activation.
            b.conv(ids[0], 0, 8, (3, 3), (1, 1), (1, 1), (1, 1, 1, 1), 4, Act::PRelu(2))
        },
    );
}

/// A tiled convolution whose consumer is a residual add: `Builder::finish` folds
/// the add into the tiled store via `res`, so this exercises the fused addend at
/// the tiled block's flat index rather than only the plain store.
#[test]
#[ignore = "needs a Vulkan device"]
fn a_tiled_conv_with_a_fused_residual_agrees_with_the_reference() {
    let input = spread(8 * 24 * 24, 5.0);
    agrees_invented(
        "a tiled 3x3 with a fused residual",
        2,
        &[Shape::new(8, 24, 24)],
        &[&input],
        |b, ids| {
            b.spatial_tiling();
            let projected =
                b.conv(ids[0], 0, 8, (3, 3), (1, 1), (1, 1), (1, 1, 1, 1), 1, Act::None);
            b.add(ids[0], projected)
        },
    );
}

/// A row-mode instance norm at a MODNet-like shape: 32 channels of 128x128.
/// Planes this large must take the workgroup-per-channel path (`row_reduce_plane`
/// selects it at 64+ elements); the fixture asserts the plan chose it, then
/// requires the device to agree with the reference.
#[test]
#[ignore = "needs a Vulkan device"]
fn a_row_mode_instance_norm_agrees_with_the_reference() {
    let input = spread(32 * 128 * 128, 0.7);
    let source = Invented::new(0);
    let plan = row_mode_plan(
        &source,
        &[Shape::new(32, 128, 128)],
        crate::nets::Kind::InstanceNorm,
        |b, ids| b.instance_norm(ids[0], 1e-5),
    );
    compare(
        "a row-mode instance norm, 32 channels of 128x128",
        plan,
        source.into_data(),
        &[&input],
    );
}

/// A row-mode instance norm at a ragged shape: 13 channels of 37x41, so neither
/// the channel count nor the plane is a multiple of anything convenient. Lanes
/// stride the plane, so partial workgroups and tail elements are the thing under
/// test.
#[test]
#[ignore = "needs a Vulkan device"]
fn a_ragged_row_mode_instance_norm_agrees_with_the_reference() {
    let input = spread(13 * 37 * 41, -0.4);
    let source = Invented::new(0);
    let plan = row_mode_plan(
        &source,
        &[Shape::new(13, 37, 41)],
        crate::nets::Kind::InstanceNorm,
        |b, ids| b.instance_norm(ids[0], 1e-5),
    );
    compare(
        "a ragged row-mode instance norm, 13 channels of 37x41",
        plan,
        source.into_data(),
        &[&input],
    );
}

/// A row-mode global average pool at a real size: the plan must choose the
/// workgroup-per-channel path and the device must agree with the reference.
#[test]
#[ignore = "needs a Vulkan device"]
fn a_row_mode_global_avg_pool_agrees_with_the_reference() {
    let input = spread(128 * 16 * 16, 1.3);
    let source = Invented::new(0);
    let plan = row_mode_plan(
        &source,
        &[Shape::new(128, 16, 16)],
        crate::nets::Kind::GlobalAvgPool,
        |b, ids| b.global_avg_pool(ids[0]),
    );
    compare(
        "a row-mode global average pool, 128 channels of 16x16",
        plan,
        source.into_data(),
        &[&input],
    );
}
