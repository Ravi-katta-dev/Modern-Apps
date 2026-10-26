    /// An add whose convolution output has a second reader stays its own dispatch.
    ///
    /// Folding it would leave the other reader with no writer: the producer's old
    /// output tensor is never allocated once the fold moves the sum.
    #[test]
    fn a_shared_convolution_output_keeps_its_add() {
        let source = Shapes::new(5);
        let mut builder = Builder::new(&source);
        let x = builder.input(Shape::new(4, 1, 4));
        let narrowed = builder.conv_same(x, 0, 4, 1, 1, Act::None);
        let summed = builder.add(x, narrowed);
        // A second reader of the convolution's output: the folded store would orphan it.
        let mixed = builder.mul(summed, narrowed);
        // Tensors 2, 3 and 4 are the file's spares; see the residual test above.
        builder.host_tensor(2, &[1]);
        builder.host_tensor(3, &[1]);
        builder.host_tensor(4, &[1]);
        let plan = builder.finish(&[mixed]).expect("builds");
        assert_eq!(
            plan.ops.iter().filter(|op| matches!(op, Op::Dispatch { kind: Kind::Add, .. })).count(),
            1,
            "the shared add must survive: {:?}",
            plan.ops
        );
    }

    /// An add held as a plan output stays its own dispatch.
    ///
    /// Nothing downstream reads it, but the host does — folding the sum into the
    /// convolution's store would leave the output binding pointing at an unwritten tensor.
    #[test]
    fn an_add_that_is_a_plan_output_keeps_its_dispatch() {
        let source = Shapes::new(5);
        let mut builder = Builder::new(&source);
        let x = builder.input(Shape::new(4, 1, 4));
        let narrowed = builder.conv_same(x, 0, 4, 1, 1, Act::None);
        let summed = builder.add(x, narrowed);
        // Both the convolution's output and the sum escape: neither may fold.
        // Tensors 2, 3 and 4 are the file's spares; see the residual test above.
        builder.host_tensor(2, &[1]);
        builder.host_tensor(3, &[1]);
        builder.host_tensor(4, &[1]);
        let plan = builder.finish(&[narrowed, summed]).expect("builds");
        assert_eq!(
            plan.ops.iter().filter(|op| matches!(op, Op::Dispatch { kind: Kind::Add, .. })).count(),
            1,
            "the output add must survive: {:?}",
            plan.ops
        );
    }

    /// An FPN-style `add(earlier, later)` stays its own dispatch.
    ///
    /// The skip side is downstream of the producing convolution, so reading it from
    /// the producer's store would read an unwritten tensor. See `Builder::add`.
    #[test]
    fn an_add_of_a_downstream_tensor_does_not_fold() {
        let source = Shapes::new(7);
        let mut builder = Builder::new(&source);
        let x = builder.input(Shape::new(4, 1, 8));
        let early = builder.conv_same(x, 0, 4, 1, 1, Act::None);
        let later = builder.conv_same(early, 2, 4, 1, 1, Act::None);
        let grown = builder.resize_to(later, 1, 8);
        // `early` is upstream of the convolution that feeds `grown`'s side... but the
        // addend that matters is `grown` itself, produced after `early`: folding the
        // add into `early`'s store would read it before it is written.
        let merged = builder.add(early, grown);
        let out = builder.conv_same(merged, 4, 4, 1, 1, Act::None);
        // Tensor 6 is the file's spare; see the residual test above.
        builder.host_tensor(6, &[1]);
        let plan = builder.finish(&[out]).expect("builds");
        assert_eq!(
            plan.ops.iter().filter(|op| matches!(op, Op::Dispatch { kind: Kind::Add, .. })).count(),
            1,
            "the FPN add must survive: {:?}",
            plan.ops
        );
    }

    /// A self-add never folds: the convolution would read the tensor it no longer writes.
    #[test]
    fn a_self_add_does_not_fold() {
        let source = Shapes::new(3);
        let mut builder = Builder::new(&source);
        let x = builder.input(Shape::new(4, 1, 4));
        let narrowed = builder.conv_same(x, 0, 4, 1, 1, Act::None);
        let doubled = builder.add(narrowed, narrowed);
        // Tensor 2 is the file's spare; see the residual test above.
        builder.host_tensor(2, &[1]);
        let plan = builder.finish(&[doubled]).expect("builds");
        assert_eq!(
            plan.ops.iter().filter(|op| matches!(op, Op::Dispatch { kind: Kind::Add, .. })).count(),
            1,
            "the self-add must survive: {:?}",
            plan.ops
        );
    }
