impl Voice {
    /// Read one `style_<name>.bin` from the voice bundle.
    ///
    /// `style_ttl` transposed to `[256, 50]` first, then `style_dp` flattened to 128, both as
    /// little-endian fp16 and nothing else — no header, because the two shapes are fixed by the
    /// architecture and a length check is therefore as strong as a table would be.
    ///
    /// Not a `.maml`: these are **inputs**, one pair per voice, handed to
    /// [`crate::vulkan::run::Net::infer_raw_many`] per utterance rather than living in a plan's
    /// weights buffer. `scripts/ml/supertonic_bundle.py` writes them, and does the transpose
    /// there because the export stores `style_ttl` position-major and this runtime is
    /// channel-major.
    pub fn read(bytes: &[u8]) -> Result<Voice, String> {
        let text_values = (net::STYLE * net::STYLE_TOKENS) as usize;
        let duration_values = duration_net::STYLE as usize;
        let wanted = (text_values + duration_values) * 2;
        if bytes.len() != wanted {
            return Err(format!("a voice file of {} bytes, not {wanted}", bytes.len()));
        }
        let values: Vec<f32> = bytes
            .chunks_exact(2)
            .map(|pair| f16_to_f32(u16::from_le_bytes([pair[0], pair[1]])))
            .collect();
        let (text, duration) = values.split_at(text_values);
        Ok(Voice { duration: duration.to_vec(), text: text.to_vec() })
    }
}

/// Synthesise one utterance.
///
/// `text` must already be NFKD; `language` is its ISO-639-1 code. See [`to_ids`] for why both
/// matter. `noise` supplies the flow's starting latent, one standard normal per value — flow
/// matching is meant to vary between calls, so the caller seeds it from the clock.
///
/// ```text
/// text + language -> ids         to_ids, over the bundle's codepoint table
/// ids + style_dp -> seconds      the duration predictor, then exp, then / SPEED
/// seconds -> frames              ceil(seconds * 44100 / 3072)
/// ids + style_ttl -> text_emb    the text encoder
/// noise + text_emb -> latent     the sampler, [`STEPS`] steps of two guidance branches
/// latent -> waveform             the vocoder
/// ```
///
/// The sampler and the vocoder run at [`pad_positions`]`(frames)` rather than `frames` itself,
/// so the int8 pointwise dispatches take the shader's wide-load path; the waveform is then
/// truncated back to `frames * SAMPLES_PER_FRAME` samples. Bit-identical to running unpadded:
/// the sampler's inputs replicate the edge (which its symmetric padding clamps to anyway),
/// the vocoder is causal (real outputs never read the tail), and the tail is sliced before
/// anything observable.
pub fn synthesise(
    stages: &mut dyn Stages,
    conditioning: &Conditioning,
    indexer: &[u8],
    voice: &Voice,
    text: &str,
    language: &str,
    noise: &dyn Fn(usize) -> Vec<f32>,
) -> Result<Vec<f32>, String> {
    let ids = to_ids(indexer, text, language)?;
    let chars = ids.len() as u32;

    // The duration predictor's sequence leads with the sentence token; the text encoder's does
    // not. Two id tensors, not one.
    let mut with_token = Vec::with_capacity(ids.len() + 1);
    with_token.push(duration_net::SENTENCE_TOKEN);
    with_token.extend_from_slice(&ids);
    let log_seconds = stages.duration(&embed_lanes(&with_token), &voice.duration)?;
    let frames = duration_net::latent_frames(duration_net::seconds(log_seconds) / SPEED);
    let padded = pad_positions(frames);

    let conditioning_text = stages.text(&embed_lanes(&ids), &voice.text)?;

    // Normalised by the real length, built out to the padded one: the real positions
    // keep the exact angles the unpadded plan used and the tail is never read. The key
    // table is over the characters, which are never padded (padding keys would steal
    // softmax mass).
    let query_angles = rotary_angles_over(&conditioning.theta, frames, padded)?;
    let key_angles = rotary_angles(&conditioning.theta, chars)?;
    let unconditional_text = unconditional_text(&conditioning.text_token, chars)?;

    // The noise covers the real frames; the tail replicates the edge so the sampler's
    // edge-padded depthwise reads what the unpadded plan's clamps produced. It stays padded
    // for all sixteen steps — the plan is recorded at the padded shape — and is sliced once,
    // below, before the vocoder.
    let mut latent = replicate_edge(
        &noise(net::LATENT as usize * frames as usize),
        net::LATENT as usize,
        frames as usize,
        padded as usize,
    )?;
    for step in 0..STEPS as usize {
        let shifts = conditioning
            .shifts
            .get(step)
            .ok_or("the conditioning holds fewer shifts than there are steps")?;
        let [conditional, unconditional] = stages.sampler_both(
            &latent,
            &conditioning_text,
            &conditioning.conditional_keys,
            &voice.text,
            &unconditional_text,
            &conditioning.unconditional_keys,
            &conditioning.unconditional_style,
            shifts,
            &query_angles,
            &key_angles,
        )?;
        latent = self::step(&latent, &conditional, &unconditional, STEPS)?;
    }

    // The vocoder runs padded and its tail is truncated: it is causal, so the real prefix
    // never read the tail and the extra samples are beyond the predicted duration anyway.
    // Truncating the waveform rather than the latent keeps every stage at one shape.
    let waveform = stages.vocoder(&latent, padded)?;
    let samples =
        frames as usize * crate::nets::supertonic_vocoder::SAMPLES_PER_FRAME as usize;
    if waveform.len() < samples {
        return Err(format!(
            "a vocoder pass over {padded} frames returned {} samples, not {samples}",
            waveform.len()
        ));
    }
    Ok(waveform[..samples].to_vec())
}

/// One Euler step: combine the two guidance branches and advance the latent.
///
/// `denoised = latent + (GUIDANCE * conditional - (GUIDANCE - 1) * unconditional) / total`, which
/// is what the export's last five nodes do once its batch is split back in two.
pub fn step(
    latent: &[f32],
    conditional: &[f32],
    unconditional: &[f32],
    total: u32,
) -> Result<Vec<f32>, String> {
    if total == 0 {
        return Err("a sampler step out of no steps".into());
    }
    if conditional.len() != latent.len() || unconditional.len() != latent.len() {
        return Err(format!(
            "a step over {} latent values against {} conditional and {} unconditional",
            latent.len(),
            conditional.len(),
            unconditional.len()
        ));
    }
    let scale = 1.0 / total as f32;
    Ok(latent
        .iter()
        .zip(conditional)
        .zip(unconditional)
        .map(|((&x, &c), &u)| {
            let velocity = net::GUIDANCE * c - (net::GUIDANCE - 1.0) * u;
            x + velocity * scale
        })
        .collect())
}
