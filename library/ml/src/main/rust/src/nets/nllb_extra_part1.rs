    #[test]
    fn the_sinusoid_is_fairseqs_concatenated_halves() {
        // Position 0's sines are all zero and its cosines all one, which distinguishes the
        // concatenated layout from the interleaved one immediately: interleaved would alternate.
        let half = D_MODEL as usize / 2;
        for channel in 0..half {
            assert_eq!(sinusoid(0, channel), 0.0, "channel {channel}");
        }
        for channel in half..D_MODEL as usize {
            assert_eq!(sinusoid(0, channel), 1.0, "channel {channel}");
        }
        // The lowest frequency is 1, and the highest is `1 / 10000` — which needs the spacing to
        // divide by `half - 1`. Dividing by `half` puts the last channel at 10000^(-511/512).
        assert!((sinusoid(1, 0) - 1.0f32.sin()).abs() < 1e-6);
        let smallest = (1.0f32 / 10_000.0).sin();
        assert!((sinusoid(1, half - 1) - smallest).abs() < 1e-6, "{}", sinusoid(1, half - 1));
    }

    #[test]
    fn split_of_covers_the_whole_vocabulary_without_gaps() {
        // The uneven split is the one place an id could fall between two ranges or land in both.
        // Every id must map to exactly one (split, row), and the row must be inside that split.
        let mut seen = 0u32;
        for split in 0..HEAD_SPLITS {
            for row in 0..split_classes(split) {
                seen += 1;
                let _ = (split, row);
            }
        }
        assert_eq!(seen, VOCAB);
        // The boundaries: the last id of each split and the first of the next.
        assert_eq!(split_of(0), (0, 0));
        assert_eq!(split_of(64_051), (0, 64_051));
        assert_eq!(split_of(64_052), (1, 0));
        assert_eq!(split_of(128_103), (1, 64_051));
        assert_eq!(split_of(128_104), (2, 0));
        assert_eq!(split_of(192_154), (2, 64_050));
        assert_eq!(split_of(192_155), (3, 0));
        assert_eq!(split_of(256_205), (3, 64_050));
        // And the mapping is the identity in order: walking ids walks (split, row) contiguously.
        let mut next = 0u32;
        for split in 0..HEAD_SPLITS {
            let base = HEAD + split * 3;
            let _ = base;
            for row in 0..split_classes(split) {
                assert_eq!(split_of(next), (split, row), "id {next}");
                next += 1;
            }
        }
        assert_eq!(next, VOCAB);
    }

    #[test]
    fn a_pass_over_nothing_or_past_the_table_is_refused() {
        let source = Shapes::new(TENSORS);
        let error = build(&source, Mode::Encode { len: 0 }).expect_err("no tokens");
        assert!(error.contains("no tokens"), "{error}");
        let source = Shapes::new(TENSORS);
        let error =
            build(&source, Mode::Encode { len: MAX_POSITIONS + 1 }).expect_err("too long");
        assert!(error.contains("positions the model has"), "{error}");
    }

    #[test]
    fn every_tensor_shape_is_stated_the_same_way_twice() {
        // `dims_of` restates the table `maml_convert.collect_nllb` writes, and `Layers` walks
        // it. This checks the two agree for every index, which is what makes `host_tensor`'s shape
        // check meaningful rather than circular.
        let mut expected: Vec<Vec<u32>> = Vec::new();
        for split in 0..HEAD_SPLITS {
            let classes = split_classes(split);
            expected.push(vec![classes, D_MODEL, 1, 1]);
            expected.push(vec![classes]);
            expected.push(vec![classes]);
        }
        let projection = |out: u32, inputs: u32| {
            vec![vec![out, inputs, 1, 1], vec![out], vec![out]]
        };
        let norm = || vec![vec![D_MODEL], vec![D_MODEL]];
        for (layers, cross) in [(ENCODER_LAYERS, false), (DECODER_LAYERS, true)] {
            for _ in 0..layers {
                expected.extend(norm());
                for _ in 0..4 {
                    expected.extend(projection(D_MODEL, D_MODEL));
                }
                if cross {
                    expected.extend(norm());
                    for _ in 0..4 {
                        expected.extend(projection(D_MODEL, D_MODEL));
                    }
                }
                expected.extend(norm());
                expected.extend(projection(FFN, D_MODEL));
                expected.extend(projection(D_MODEL, FFN));
            }
            expected.extend(norm());
        }
        assert_eq!(expected.len(), TENSORS);
        for (index, want) in expected.iter().enumerate() {
            assert_eq!(&dims_of(index), want, "tensor {index}");
        }
    }
