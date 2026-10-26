/// The embedded, scaled and positioned source for `ids`, in the channel-major layout the plan
/// wants.
///
/// The `[1024, 1, len]` fp16 input to [`Mode::Encode`], as f32 for the caller to upload. See the
/// module docs for why this is on the host and for the `+ 2` on the position.
///
/// `past` is how many positions precede these ids, which is 0 for the encoder and the step number
/// for the decoder — fairseq's `past_key_values_length`, and it lands in the same arithmetic.
pub fn embed_positions(
    weights: Reader<'_>,
    ids: &[u32],
    past: u32,
) -> Result<Vec<f32>, String> {
    let width = D_MODEL as usize;
    let mut out = vec![0.0f32; width * ids.len()];
    let scale = (D_MODEL as f32).sqrt();
    for (at, &id) in ids.iter().enumerate() {
        if id >= VOCAB {
            return Err(format!("token {id} is past the {VOCAB}-entry vocabulary"));
        }
        let (split, row) = split_of(id);
        let kernel = HEAD + split * 3;
        let embedding = weights.int8_row(
            kernel,
            kernel + 1,
            &[split_classes(split), D_MODEL, 1, 1],
            row,
        )?;
        let position = past + at as u32 + 1 + PADDING_IDX;
        // `make_weights(num_positions + padding_idx + 1, ...)`, so the table's last row is
        // `MAX_POSITIONS + PADDING_IDX`.
        if position > MAX_POSITIONS + PADDING_IDX {
            return Err(format!("position {position} is past the model's table"));
        }
        for (channel, value) in embedding.iter().enumerate() {
            // Channel-major: this runtime indexes `[c, h, w]`, and the export is `[w, c]`.
            let slot = out
                .get_mut(channel * ids.len() + at)
                .ok_or("an embedding row is wider than d_model")?;
            *slot = value * scale + sinusoid(position, channel);
        }
    }
    Ok(out)
}

/// fairseq's sinusoidal position, channel `channel` of position `position`.
///
/// Not the interleaved `sin, cos, sin, cos` of the original transformer paper: the halves are
/// **concatenated**, so channels `0..512` are all the sines and `512..1024` all the cosines. And
/// the frequency spacing divides `ln(10000)` by `half_dim - 1`, not by `half_dim`.
///
/// `padding_idx` is 1 and its row is zeroed upstream, which never matters here because
/// [`embed_positions`] never asks for it — the smallest position it can produce is 2.
pub(crate) fn sinusoid(position: u32, channel: usize) -> f32 {
    let half = D_MODEL as usize / 2;
    let index = channel % half;
    let spacing = (10_000.0f32).ln() / (half as f32 - 1.0);
    let angle = position as f32 * (-(index as f32) * spacing).exp();
    if channel < half {
        angle.sin()
    } else {
        angle.cos()
    }
}
