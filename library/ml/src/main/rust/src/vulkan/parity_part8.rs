// Device parity fixtures (runtime work). See `parity.rs` for the helpers.

/// A file whose plan reads only tensors deep inside it, behind big unused ones.
///
/// Tensors: 0 big, 1-2 an early gamma/beta, 3 big, 4-6 an int8 1x1 conv, 7-8 a late gamma/beta.
/// The big ones have odd lengths so the window's start is not a tensor boundary rounded by luck.
fn deep_window_fixture() -> (Weights, Plan, Plan) {
    const C: u32 = 64;
    const W: u32 = 9;
    let kernel: Vec<i8> = (0..C * C).map(|i| (i % 7) as i8 - 3).collect();
    let tensors = vec![
        Fixture::F16(vec![3_000_001], spread(3_000_001, 0.3)),
        Fixture::F16(vec![C], spread(C as usize, 1.7)),
        Fixture::F16(vec![C], spread(C as usize, 2.9)),
        Fixture::F16(vec![2_500_003], spread(2_500_003, 0.9)),
        Fixture::I8(vec![C, C, 1, 1], kernel),
        Fixture::F16(vec![C], vec![1.0 / 16.0; C as usize]),
        Fixture::F16(vec![C], spread(C as usize, 0.4)),
        Fixture::F16(vec![C], spread(C as usize, 1.1)),
        Fixture::F16(vec![C], spread(C as usize, 2.3)),
    ];
    let blob = write_mixed(graph::SUPERTONIC_VE, &tensors);
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the fixture blob parses");
    let dims = |index: usize| -> Vec<u32> {
        match index {
            0 => vec![3_000_001],
            3 => vec![2_500_003],
            4 => vec![C, C, 1, 1],
            _ => vec![C],
        }
    };

    let mut b = Builder::new(&weights);
    let x = b.input(Shape::new(C, 1, W));
    let y = b.conv_int8(x, 4, C, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::Relu);
    let y = b.layer_norm(y, 7, 1e-5);
    for index in 0..4 {
        b.host_tensor(index, &dims(index));
    }
    let deep = b.finish(&[y]).expect("the deep plan builds");

    let mut b = Builder::new(&weights);
    let x = b.input(Shape::new(C, 1, W));
    let y = b.layer_norm(x, 1, 1e-5);
    for index in (0..9).filter(|&i| i != 1 && i != 2) {
        b.host_tensor(index, &dims(index));
    }
    let early = b.finish(&[y]).expect("the early plan builds");
    (weights, deep, early)
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_net_uploads_only_the_window_its_plan_reads() {
    let (weights, deep, early) = deep_window_fixture();
    let input = spread(64 * 9, 0.6);
    let host_deep = run_multi(&deep, weights.data(), &[&input]).expect("the interpreter runs");
    let host_early = run_multi(&early, weights.data(), &[&input]).expect("the interpreter runs");
    let total = weights.data().len() as u64;
    let offset_of = |index: usize| u64::from(weights.tensor(index).expect("it exists").offset);
    let kernel_at = offset_of(4);

    let mut net = Net::new(device(), deep.clone(), &weights, RESCALE_ONLY).expect("it records");
    let (lo, hi) = net.weights_window();
    assert!(lo > 0 && lo <= kernel_at && kernel_at - lo < 256, "window starts at {lo}");
    assert_eq!(hi, total, "the last tensor is read, so the window runs to the end");
    assert!(hi - lo < total / 100, "{} of {total} bytes uploaded", hi - lo);
    let windowed = net.infer_raw(&input).expect("the windowed net runs");
    matches("a windowed net", &host_deep, &windowed);

    let mut whole = Net::whole(device(), deep.clone(), &weights, RESCALE_ONLY).expect("it records");
    assert_eq!(whole.weights_window(), (0, total));
    let all = whole.infer_raw(&input).expect("the whole-file net runs");
    assert_eq!(windowed, all, "the window changes where bytes live, not what is computed");

    // Outside the window: refused, and the net keeps working at the plan it had.
    let refused = net.rebuild(early.clone());
    assert!(refused.is_err(), "a plan reading below the window was installed");
    let again = net.infer_raw(&input).expect("the net survives the refused rebuild");
    assert_eq!(again, windowed);

    let mut both = Net::covering(device(), deep, &[&early], &weights, RESCALE_ONLY)
        .expect("the covering net records");
    let (lo, _) = both.weights_window();
    assert!(lo <= offset_of(1) && lo > 0, "covering window at {lo}");
    matches("a covering net", &host_deep, &both.infer_raw(&input).expect("it runs"));
    both.rebuild(early).expect("the covered plan installs");
    matches("a covering net rebuilt", &host_early, &both.infer_raw(&input).expect("it runs"));
}
