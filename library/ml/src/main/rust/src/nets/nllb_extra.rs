#[cfg(test)]
mod tests {
    use super::super::tests::{assert_no_aliasing, Shapes};
    use super::super::{Kind, Op, Plan, Shape};
    use super::super::nllb::*;

    /// A short sentence: a language token, six pieces and `</s>`.
    const LEN: u32 = 8;

    /// `Act::Relu`'s code in `common.glsl`. `Act::code` is private, so the activation the FFN
    /// folds is asserted as the number the shader reads rather than through the enum.
    const ACT_RELU: u32 = 1;

    fn plan(mode: Mode) -> (Shapes, Plan) {
        let source = Shapes::new(TENSORS);
        let plan = build(&source, mode).expect("the pass builds");
        (source, plan)
    }

    #[test]
    fn the_layout_matches_the_converter() {
        // The numbers `maml_convert.py --graph nllb600 --print-layers` reports for this graph. A
        // disagreement here is a plan that reads one layer's weights as another's.
        assert_eq!(ENCODER_LAYER_TENSORS, 22);
        assert_eq!(DECODER_LAYER_TENSORS, 36);
        assert_eq!((HEAD, ENCODER, ENCODER_NORM), (0, 12, 276));
        assert_eq!((DECODER, DECODER_NORM), (278, 710));
        assert_eq!(TENSORS, 712);
        assert_eq!(INT8_CONVS, 196);
        assert_eq!((split_classes(0), split_classes(1)), (64_052, 64_052));
        assert_eq!((split_classes(2), split_classes(3)), (64_051, 64_051));
        assert_eq!(
            split_classes(0) + split_classes(1) + split_classes(2) + split_classes(3),
            VOCAB
        );
    }

    #[test]
    fn the_parameter_total_matches_the_checkpoint() {
        // What the file holds, from the layout `every_tensor_shape_is_stated_the_same_way_twice`
        // pins, against the checkpoint plus exactly the two things quantising adds.
        let total: u64 = (0..TENSORS)
            .map(|index| dims_of(index).iter().map(|&d| u64::from(d)).product::<u64>())
            .sum();

        // One fp16 scale per output channel of each of the 196 int8 convolutions.
        let layer_scales = u64::from(4 * D_MODEL + FFN + D_MODEL);
        let scales = u64::from(VOCAB)
            + ENCODER_LAYERS as u64 * layer_scales
            + DECODER_LAYERS as u64 * (layer_scales + u64::from(4 * D_MODEL));
        assert_eq!(scales, 526_542);
        // And a zero bias for each head split, which a tied projection has no weight for.
        let synthesised = u64::from(VOCAB);

        assert_eq!(total, 615_073_792 + scales + synthesised);

        // ~588.4 MiB on disk. Everything but the 196 kernels is fp16, and the kernels are 99.8%
        // of the elements — which is the whole reason the file is barely over the parameter
        // count in bytes.
        let kernels = u64::from(VOCAB) * u64::from(D_MODEL)
            + ENCODER_LAYERS as u64 * u64::from(D_MODEL) * u64::from(4 * D_MODEL + 2 * FFN)
            + DECODER_LAYERS as u64 * u64::from(D_MODEL) * u64::from(8 * D_MODEL + 2 * FFN);
        assert_eq!(kernels, 614_676_480);
        let file = kernels + (total - kernels) * 2;
        assert!((588 << 20..589 << 20).contains(&file), "{file} bytes");
    }

    /// The tensor ranges each pass reads on the device. Everything else it names.
    ///
    /// Stated here rather than returned by [`build`] because it is the thing under test: a pass
    /// that read the wrong range would name the right one and still be wrong.
    fn read_by(mode: Mode) -> Vec<std::ops::Range<usize>> {
        let mut ranges = Vec::new();
        let cross: Vec<_> = (0..DECODER_LAYERS).map(cross_kv).collect();
        match mode {
            Mode::Encode { .. } => {
                ranges.push(ENCODER..DECODER);
                ranges.extend(cross);
            }
            Mode::DecodeStep { .. } => {
                // The decode step computes the tied head itself, so it reads both ends of the file,
                // minus the cross-attention K and V the encode pass ran.
                ranges.push(HEAD..ENCODER);
                ranges.extend(
                    (DECODER..TENSORS)
                        .filter(|index| !cross.iter().any(|range| range.contains(index)))
                        .map(|index| index..index + 1),
                );
            }
        }
        ranges
    }

    #[test]
    fn the_passes_cover_the_file_and_every_one_of_them_builds() {
        // `Builder::finish` only checks that a tensor is read *or* named, so this is what stops
        // naming being used to hide a layer the device never touches. Together the passes must read
        // every index.
        let mut covered = std::collections::BTreeSet::new();
        for mode in [
            Mode::Encode { len: LEN },
            Mode::DecodeStep { src_len: LEN },
        ] {
            let source = Shapes::new(TENSORS);
            build(&source, mode).unwrap_or_else(|e| panic!("{mode:?}: {e}"));
            covered.extend(read_by(mode).into_iter().flatten());
        }
        assert_eq!(covered.into_iter().collect::<Vec<_>>(), (0..TENSORS).collect::<Vec<_>>());
    }

    #[test]
    fn the_encoder_is_twelve_pre_norm_layers() {
        let (_, plan) = plan(Mode::Encode { len: LEN });
        let mut counts = std::collections::BTreeMap::new();
        for op in &plan.ops {
            if let Op::Dispatch { kind, .. } = op {
                *counts.entry(super::super::tests::name_of(*kind)).or_insert(0usize) += 1;
            }
        }
        // Six int8 convolutions per layer: q, k, v, the output projection, fc1 and fc2 - and the
        // decoder's cross-attention K and V, computed here once per sentence.
        assert_eq!(
            counts.get("ConvInt8"),
            Some(&(ENCODER_LAYERS * 6 + DECODER_LAYERS * 2)),
            "{counts:?}"
        );
        // Three norms per layer would be post-norm. Pre-norm is two, plus one at the end.
        assert_eq!(counts.get("LayerNorm"), Some(&(ENCODER_LAYERS * 2 + 1)), "{counts:?}");
        assert_eq!(counts.get("AttnScores"), Some(&ENCODER_LAYERS), "{counts:?}");
        assert_eq!(counts.get("Softmax"), Some(&ENCODER_LAYERS), "{counts:?}");
        assert_eq!(counts.get("AttnApply"), Some(&ENCODER_LAYERS), "{counts:?}");
        // Two residuals per layer — half of which fold into their producing
        // convolution's store. See `Builder::add`.
        assert_eq!(counts.get("Add"), Some(&12), "{counts:?}");
        assert_eq!(counts.len(), 6, "{counts:?}");
        // And the FFN inner projection folds ReLU — `config.json`'s `activation_function: relu` —
        // one per layer, on the `FFN`-wide projection only.
        let mut relu = 0;
        for op in &plan.ops {
            if let Op::Dispatch { push, .. } = op {
                if push.out_c == FFN {
                    assert_eq!(push.act, ACT_RELU, "the FFN inner projection is ReLU: {push:?}");
                    relu += 1;
                }
            }
        }
        assert_eq!(relu, ENCODER_LAYERS, "{counts:?}");
    }

    #[test]
    fn the_encoder_keeps_its_length_and_width() {
        let (_, plan) = plan(Mode::Encode { len: LEN });
        assert_eq!(plan.input().unwrap().shape, Shape::new(D_MODEL, 1, LEN));
        assert_eq!(plan.output().unwrap().shape, Shape::new(D_MODEL, 1, LEN));
        assert_no_aliasing(&plan);
    }

    #[test]
    fn the_attention_score_maps_are_square_in_the_encoder() {
        // Self-attention over one sentence, so queries and keys are the same positions. The
        // cross-attention in the decode step is what makes them differ.
        let (_, plan) = plan(Mode::Encode { len: LEN });
        for op in &plan.ops {
            if let Op::Dispatch { kind: Kind::AttnScores, push, .. } = op {
                assert_eq!((push.group, push.out_h, push.out_w), (HEADS, LEN, LEN), "{push:?}");
            }
        }
    }

    #[test]
    fn the_encode_pass_leaves_the_cross_caches_where_the_decode_step_reads_them() {
        // The whole of the cross-attention cache rests on this: two differently shaped plans
        // agreeing on where 24 persistent tensors are. If they disagree the decoder attends over
        // the other plan's activations - no crash, no shape error, fluent wrong output.
        let (_, encode) = plan(Mode::Encode { len: LEN });
        let (_, step) = plan(Mode::DecodeStep { src_len: LEN });
        let caches = DECODER_LAYERS * 2;
        assert_eq!(encode.pinned[..caches], step.pinned[..caches]);
        for binding in &encode.pinned[..caches] {
            assert_eq!(binding.shape, Shape::new(D_MODEL, 1, LEN));
        }

        // The encode pass writes every one of them, whole.
        let written: Vec<_> = encode
            .ops
            .iter()
            .filter_map(|op| match *op {
                Op::Copy { dst, elems, .. } => Some((dst, elems)),
                _ => None,
            })
            .collect();
        for binding in &encode.pinned[..caches] {
            assert!(
                written.contains(&(binding.at, D_MODEL * LEN)),
                "the encode pass never fills the cache at {}",
                binding.at
            );
        }

        // And the decode step's cross-attention reads them rather than anything it computed.
        let starts: Vec<u32> = encode.pinned[..caches].iter().map(|b| b.at).collect();
        let mut reads = 0;
        for op in &step.ops {
            match op {
                Op::Dispatch { kind: Kind::AttnScores, push, .. } => {
                    assert!(starts.contains(&push.in1), "cross K from {}: {push:?}", push.in1);
                    reads += 1;
                }
                Op::Dispatch { kind: Kind::AttnApply, push, .. } => {
                    assert!(starts.contains(&push.in1), "cross V from {}: {push:?}", push.in1);
                    reads += 1;
                }
                _ => {}
            }
        }
        assert_eq!(reads, caches);
        assert_no_aliasing(&encode);
    }

    #[test]
    fn a_decode_step_is_twelve_layers_of_two_attentions() {
        let (_, plan) = plan(Mode::DecodeStep { src_len: LEN });
        let mut counts = std::collections::BTreeMap::new();
        for op in &plan.ops {
            if let Op::Dispatch { kind, .. } = op {
                *counts.entry(super::super::tests::name_of(*kind)).or_insert(0usize) += 1;
            }
        }
        // Eight int8 convolutions per layer - the self-attention's four, the cross-attention's
        // query and output, fc1 and fc2 - and the four splits of the tied head. The cross K and V
        // are the encode pass's.
        assert_eq!(
            counts.get("ConvInt8"),
            Some(&(DECODER_LAYERS * 8 + HEAD_SPLITS)),
            "{counts:?}"
        );
        // Three norms per layer, pre-norm, plus the trailing one.
        assert_eq!(counts.get("LayerNorm"), Some(&(DECODER_LAYERS * 3 + 1)), "{counts:?}");
        // Self-attention is cached and single-query; the cross-attention is not.
        assert_eq!(counts.get("AttnScoresCached"), Some(&DECODER_LAYERS), "{counts:?}");
        assert_eq!(counts.get("AttnApplyCached"), Some(&DECODER_LAYERS), "{counts:?}");
        assert_eq!(counts.get("AttnScores"), Some(&DECODER_LAYERS), "{counts:?}");
        assert_eq!(counts.get("AttnApply"), Some(&DECODER_LAYERS), "{counts:?}");
        // The self-attention softmax is the prefix-bounded one; the cross-attention's is not.
        assert_eq!(counts.get("Softmax"), Some(&DECODER_LAYERS), "{counts:?}");
        assert_eq!(counts.get("SoftmaxPrefix"), Some(&DECODER_LAYERS), "{counts:?}");
        // K and V into the cache, per layer.
        assert_eq!(counts.get("CacheWrite"), Some(&(DECODER_LAYERS * 2)), "{counts:?}");
        // Three residuals per layer — half of which fold into their producing
        // convolution's store. See `Builder::add`.
        assert_eq!(counts.get("Add"), Some(&18), "{counts:?}");
        // And the greedy pick, on the device: the partials reduce plus the fold.
        assert_eq!(counts.get("Argmax"), Some(&1), "{counts:?}");
        assert_eq!(counts.get("ArgmaxFold"), Some(&1), "{counts:?}");
        assert_no_aliasing(&plan);
    }

    #[test]
    fn a_decode_step_takes_only_the_token_and_returns_only_the_next_one() {
        let (_, plan) = plan(Mode::DecodeStep { src_len: LEN });
        // The token, and nothing else: the self-attention cache and the cross-attention K and V are
        // on the device. The encoder output used to be uploaded here on every token.
        assert_eq!(plan.inputs.len(), 1);
        assert_eq!(plan.inputs[0].shape, Shape::new(D_MODEL, 1, 1));
        // `Builder::argmax`'s two lanes, rather than 256,206 logits.
        assert_eq!(plan.outputs.len(), 1);
        assert_eq!(plan.outputs[0].shape, Shape::new(2, 1, 1));
    }

    #[test]
    fn the_decode_plan_does_not_depend_on_the_step() {
        // The property the whole record-once design rests on. Two steps of the same translation
        // produce the same key, so `Reshaped::at` matches and never re-records; and because the
        // plan is identical, the arena offsets - including the caches' - are identical too, which
        // is what lets the cache survive from one submit to the next.
        let (_, first) = plan(Mode::DecodeStep { src_len: LEN });
        let (_, again) = plan(Mode::DecodeStep { src_len: LEN });
        assert_eq!(first.ops, again.ops);
        assert_eq!(first.arena_elems, again.arena_elems);
        assert_eq!(Mode::DecodeStep { src_len: LEN }, Mode::DecodeStep { src_len: LEN });
    }

    #[test]
    fn the_caches_are_built_for_the_longest_decode_the_loop_can_run() {
        // Sized to `MAX_DECODE_POSITIONS`, not to a step, so a plan recorded once covers every
        // token. If this and `post::translate::MAX_TOKENS` ever disagree the loop either wastes
        // arena or silently drops positions at the end of a long sentence.
        let (_, plan) = plan(Mode::DecodeStep { src_len: LEN });
        let mut writes = 0;
        for op in &plan.ops {
            match op {
                Op::Dispatch { kind: Kind::CacheWrite, push, .. } => {
                    writes += 1;
                    assert_eq!(push.in_h, MAX_DECODE_POSITIONS, "{push:?}");
                    assert_eq!(push.in_c, D_MODEL, "the row stride is d_model: {push:?}");
                    assert_eq!(push.count, D_MODEL, "a position is d_model long: {push:?}");
                }
                // The score map is as wide as the cache, and the bound comes from the step.
                Op::Dispatch { kind: Kind::AttnScoresCached, push, .. } => {
                    assert_eq!((push.out_h, push.out_w), (1, MAX_DECODE_POSITIONS), "{push:?}");
                    assert_ne!(push.dyn_keys, 0, "the key count must come from the step");
                }
                Op::Dispatch { kind: Kind::AttnApplyCached, push, .. } => {
                    assert_eq!((push.in_w, push.out_c), (MAX_DECODE_POSITIONS, D_MODEL), "{push:?}");
                    assert_ne!(push.dyn_keys, 0, "the key count must come from the step");
                }
                // Cross-attention: one query over the source, which is a different length and a
                // fixed one, so it stays static.
                Op::Dispatch { kind: Kind::AttnScores, push, .. } => {
                    assert_eq!((push.out_h, push.out_w), (1, LEN), "{push:?}");
                    assert_eq!(push.dyn_keys, 0, "cross-attention is not prefix-bounded");
                }
                _ => {}
            }
        }
        assert_eq!(writes, DECODER_LAYERS * 2, "a K and a V per layer");
        assert_no_aliasing(&plan);
    }

    #[test]
    fn a_decode_step_over_nothing_is_refused() {
        let source = Shapes::new(TENSORS);
        let error =
            build(&source, Mode::DecodeStep { src_len: 0 }).expect_err("no source");
        assert!(error.contains("no source"), "{error}");
    }

    #[test]
    fn a_decode_step_is_single_position_where_it_counts() {
        // Why `Kind::ConvVecInt8` exists. A decode step is one position almost throughout, and the
        // tiled kind's workgroup count is `out_c.div_ceil(16) * positions.div_ceil(16)` — so at one
        // position it pads 15 of every 16 tile columns and stores from 8 of every 64 invocations.
        let (_, plan) = plan(Mode::DecodeStep { src_len: LEN });
        let int8: Vec<_> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind, push, invocations } => {
                    (super::super::tests::name_of(*kind) == "ConvInt8")
                        .then_some((*kind, *push, *invocations))
                }
                _ => None,
            })
            .collect();
        assert_eq!(int8.len(), DECODER_LAYERS * 8 + HEAD_SPLITS);

        // Every one of them: with the cross-attention K and V moved to the encode pass, a decode
        // step does no multi-position work at all.
        for (kind, push, _) in &int8 {
            assert_eq!(*kind, Kind::ConvVecInt8, "{push:?}");
        }
        let vector = &int8;
        // One workgroup per group of `CONV_VEC_ROWS` output channels, 64 invocations each.
        //
        // Derived rather than written out: the group size is an occupancy tuning knob and was
        // eight when this was written. Hard-coding it here meant tuning it failed a test about
        // NLLB's decode shape, which is not what this is checking.
        for (_, push, invocations) in vector {
            assert_eq!(push.out_h * push.out_w, 1, "{push:?}");
            assert_eq!(push.count, push.out_c.div_ceil(super::super::CONV_VEC_ROWS), "{push:?}");
            assert_eq!(*invocations, push.count * 64, "{push:?}");
        }
    }

    #[test]
    fn the_head_is_four_ops_over_disjoint_class_ranges() {
        // Together the four splits are the whole vocabulary, which is what the device argmax
        // reduces over. The last two splits are one class short each.
        let source = Shapes::new(TENSORS);
        let plan = build(&source, Mode::DecodeStep { src_len: LEN })
            .expect("the step builds");
        let heads: Vec<_> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::ConvVecInt8, push, .. } if push.out_c > FFN => {
                    Some(push.out_c)
                }
                _ => None,
            })
            .collect();
        assert_eq!(heads, vec![64_052, 64_052, 64_051, 64_051]);
        assert_eq!(heads.iter().sum::<u32>(), VOCAB);
        // The argmax runs over all of them at once, in id order, then folds the partials.
        let argmax: Vec<_> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::Argmax, push, .. } => Some(push.count),
                _ => None,
            })
            .collect();
        assert_eq!(argmax, vec![VOCAB]);
        assert_eq!(
            plan.ops
                .iter()
                .filter(|op| matches!(op, Op::Dispatch { kind: Kind::ArgmaxFold, .. }))
                .count(),
            1
        );
    }

    #[test]
    fn the_head_binds_under_the_guaranteed_range() {
        // Why the split exists and why it is four: the whole 256,206 x 1024 int8 table is ~250
        // MiB against a guaranteed 128 MiB, and two splits would each sit just under it the way
        // the whole small100-era 128k table did — too tight. A quarter plus its scale and bias is
        // comfortably inside.
        let whole = u64::from(VOCAB) * u64::from(D_MODEL);
        assert!(whole > 250 << 20, "the whole table is {whole} bytes");
        // Two splits would be 128,103 classes each — just under the 128 MiB floor the way the
        // whole small100-era 128k table was. Too tight for a binding plus its scale and bias.
        let half = 128_103u64 * u64::from(D_MODEL);
        assert!(half > 120 << 20, "a half would be {half} bytes: too tight");
        let quarter = u64::from(CLASSES_PER_SPLIT) * u64::from(D_MODEL)
            + u64::from(CLASSES_PER_SPLIT) * 4;
        assert!(quarter < 96 << 20, "a quarter is {quarter} bytes");
    }

    #[test]
    fn the_first_token_sits_at_position_two() {
        // fairseq's `cumsum(mask) * mask + padding_idx`, which is the off-by-two that produces
        // fluent, plausible, wrong output. Asserted through the one arithmetic that uses it.
        assert_eq!(1 + PADDING_IDX, 2);
        // The decoder at step `s` has `past = s` and one id, and lands on `s + 2` by the same
        // expression: `past + at + 1 + padding_idx` with `at` zero.
        for step in 0..4u32 {
            assert_eq!(step + 1 + PADDING_IDX, step + 2);
        }
    }
    include!("nllb_extra_part1.rs");
}
