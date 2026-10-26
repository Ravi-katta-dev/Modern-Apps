impl Net {
    /// Fill `input_scratch` for [`Net::infer_raw_many`], converting to fp16 only the inputs whose
    /// values changed since the previous call.
    ///
    /// Supertonic's sampler resubmits the same conditioning sixteen times an utterance and only
    /// the latent changes; converting all 190K values element by element was 3-8 ms of every
    /// ~95 ms step on a Pixel 8. Exact: an element's cached fp16 is reused only when its f32 bits
    /// equal the bits it was converted from, whatever the plan or the input boundaries.
    fn stage_inputs(&mut self, inputs: &[&[f32]]) -> Result<(), String> {
        let mut total = 0usize;
        for (binding, values) in self.plan.inputs.iter().zip(inputs) {
            let wanted = binding.shape.len() as usize;
            if values.len() != wanted {
                return Err(format!(
                    "{} values for an input of {:?}, which holds {wanted}",
                    values.len(),
                    binding.shape
                ));
            }
            total += wanted;
        }
        let fresh = self.input_seen.len() != total;
        if fresh {
            self.input_seen.clear();
            self.input_seen.resize(total, 0);
            self.input_cache.clear();
            self.input_cache.resize(total, 0);
        }
        let mut at = 0usize;
        for values in inputs {
            let end = at + values.len();
            let seen = &mut self.input_seen[at..end];
            let unchanged = !fresh && values.iter().zip(seen.iter()).all(|(v, s)| v.to_bits() == *s);
            if !unchanged {
                let cache = &mut self.input_cache[at..end];
                for ((value, bits), half) in values.iter().zip(seen.iter_mut()).zip(cache) {
                    *bits = value.to_bits();
                    *half = preprocess::f32_to_f16(*value);
                }
            }
            at = end;
        }
        self.input_scratch.clear();
        self.input_scratch.extend_from_slice(&self.input_cache);
        Ok(())
    }
}
