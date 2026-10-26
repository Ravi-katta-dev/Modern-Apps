use super::supertonic_vocoder::{COMPRESS, LATENT, PACKED, SAMPLES_PER_POSITION};

/// Unpack a `[144, frames]` latent into the `[24, 6 * frames]` the plan reads.
///
/// **Not** a flat reinterpretation. The export reshapes `[144, L]` to `[24, 6, L]`, transposes
/// the last two axes and flattens, so position `p` of channel `c` comes from
/// `latent[c * 6 + p % 6][p / 6]`. Assuming a plain reshape correlated with the reference at
/// 0.009 rather than 0.99, so this is worth a fixture of its own.
pub fn unpack_latent(latent: &[f32], frames: usize) -> Result<Vec<f32>, String> {
    let packed = PACKED as usize;
    let compress = COMPRESS as usize;
    if latent.len() != packed * frames {
        return Err(format!(
            "{} latent values for {packed} channels over {frames} frames",
            latent.len()
        ));
    }
    let positions = frames * compress;
    let mut out = vec![0.0f32; LATENT as usize * positions];
    for channel in 0..LATENT as usize {
        for position in 0..positions {
            let source = (channel * compress + position % compress) * frames + position / compress;
            out[channel * positions + position] = latent[source];
        }
    }
    Ok(out)
}

/// Frames the vocoder plan runs at: `frames` rounded up to even, so its `6 * frames` positions
/// are a multiple of four and every int8 GEMM takes `conv_point_int8.comp`'s aligned path.
///
/// Exact: every convolution here is causal (all padding on the left, see `along`) and the layer
/// norms are over channels, so no real position ever reads the padded tail.
pub fn padded_frames(frames: u32) -> u32 {
    frames.next_multiple_of(2)
}

/// [`unpack_latent`], widened to [`padded_frames`] with a zero tail on every channel.
pub fn unpack_latent_padded(latent: &[f32], frames: usize) -> Result<Vec<f32>, String> {
    let unpacked = unpack_latent(latent, frames)?;
    let compress = COMPRESS as usize;
    let real = frames * compress;
    let padded = padded_frames(frames as u32) as usize * compress;
    let mut out = vec![0.0f32; LATENT as usize * padded];
    for (row, chunk) in unpacked.chunks_exact(real).enumerate() {
        out[row * padded..row * padded + real].copy_from_slice(chunk);
    }
    Ok(out)
}

/// [`super::supertonic_vocoder::interleave`] of a [`padded_frames`] plan's `[512, 1, 6 * padded]`
/// output, dropping the tail: `sample[t * 512 + s] = out[s][t]` for the first `6 * frames` `t`.
pub fn interleave_padded(channelled: &[f32], frames: usize) -> Result<Vec<f32>, String> {
    let compress = COMPRESS as usize;
    let channels = SAMPLES_PER_POSITION as usize;
    let real = frames * compress;
    let padded = padded_frames(frames as u32) as usize * compress;
    if real == 0 || channelled.len() != channels * padded {
        return Err(format!("{} values are not {channels} rows of {padded} positions", channelled.len()));
    }
    let mut out = vec![0.0f32; channels * real];
    for (channel, row) in channelled.chunks_exact(padded).enumerate() {
        for (position, &value) in row[..real].iter().enumerate() {
            out[position * channels + channel] = value;
        }
    }
    Ok(out)
}
#[cfg(test)]
mod padded_tests {
    use super::*;

    #[test]
    fn frames_round_up_to_even() {
        assert_eq!(padded_frames(49), 50);
        assert_eq!(padded_frames(50), 50);
        assert_eq!((padded_frames(49) * COMPRESS) % 4, 0);
    }

    #[test]
    fn the_padded_latent_is_the_unpacked_one_plus_a_zero_tail() {
        let frames = 3;
        let latent: Vec<f32> = (0..PACKED as usize * frames).map(|v| v as f32 + 1.0).collect();
        let plain = unpack_latent(&latent, frames).expect("unpacks");
        let wide = unpack_latent_padded(&latent, frames).expect("unpacks padded");
        let (real, padded) = (frames * COMPRESS as usize, 4 * COMPRESS as usize);
        assert_eq!(wide.len(), LATENT as usize * padded);
        for row in 0..LATENT as usize {
            assert_eq!(&wide[row * padded..row * padded + real], &plain[row * real..(row + 1) * real]);
            assert!(wide[row * padded + real..(row + 1) * padded].iter().all(|&v| v == 0.0));
        }
        let out: Vec<f32> = (0..SAMPLES_PER_POSITION as usize * padded).map(|v| v as f32).collect();
        let real_rows: Vec<f32> = out.chunks_exact(padded).flat_map(|row| &row[..real]).copied().collect();
        assert_eq!(
            interleave_padded(&out, frames).expect("interleaves"),
            super::super::supertonic_vocoder::interleave(&real_rows)
        );
    }
}